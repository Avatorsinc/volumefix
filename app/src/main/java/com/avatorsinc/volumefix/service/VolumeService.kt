package com.avatorsinc.volumefix.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import com.avatorsinc.volumefix.R
import com.avatorsinc.volumefix.Volume
import com.avatorsinc.volumefix.ui.MainActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Timer
import java.util.TimerTask
import kotlin.collections.HashMap

class VolumeService : Service() {

    companion object {
        const val NOTIFICATION_TITLE = "volumefix"
        const val NOTIFICATION_DESCRIPTION = "Service is running in background"
        const val NOTIFICATION_CHANNEL_ID = "VolumeService"
        const val NOTIFICATION_ID = 4455
        const val APP_SHARED_PREFERENCES = "volumefix_shared_preferences"
        const val LOCKS_KEY = "locks_key"

        const val MODE_RINGER_SETTING = "mode_ringer"
    }

    private lateinit var mAudioManager: AudioManager
    private lateinit var mNotificationManager: NotificationManager
    private lateinit var mVolumeProvider: VolumeProvider
    private var mVolumeListenerHandler: Handler? = null
    private var mVolumeListener: (() -> Unit)? = null
    private var mModeListener: (() -> Unit)? = null
    private val mBinder = LocalBinder()
    private var mVolumeLock = HashMap<Int, Int>()
    private var mMode: Int = 2
    private var mTimer: Timer? = null

    override fun onCreate() {
        super.onCreate()

        mAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mVolumeProvider = VolumeProvider(this)

        loadPreferences()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            mMode = Settings.Global.getInt(contentResolver, MODE_RINGER_SETTING, AudioManager.RINGER_MODE_NORMAL)
        }

        // Commented out DND permission handling since MDM grants permission
        // setupNotificationManager()
        // blockDoNotDisturb()

        // Instead, forcefully set the interruption filter if access is granted.
        try {
            if (mNotificationManager.isNotificationPolicyAccessGranted) {
                mNotificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                Log.d("VolumeService", "Do Not Disturb mode disabled (via MDM)")
            }
        } catch (e: Exception) {
            Log.e("VolumeService", "Error disabling DND: ${e.message}")
        }

        monitorAndBlockDND()
        registerObservers()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            tryShowNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun getVolumes(): List<Volume> = mVolumeProvider.getVolumes()

    @Synchronized
    fun startLocking() {
        mTimer = Timer()
        mTimer?.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    checkVolumes()
                }
            },
            0, 25
        )
    }

    @Synchronized
    fun stopLocking() {
        mTimer?.cancel()
        mTimer = null
    }

    fun registerOnVolumeChangeListener(handler: Handler, listener: () -> Unit) {
        mVolumeListenerHandler = handler
        mVolumeListener = listener
    }

    fun unregisterOnVolumeChangeListener() {
        mVolumeListener = null
    }

    fun registerOnModeChangeListener(listener: () -> Unit) {
        mModeListener = listener
    }

    fun unregisterOnModeChangeListener() {
        mModeListener = null
    }

    @Synchronized
    fun addLock(stream: Int, volume: Int) {
        mVolumeLock[stream] = volume
        savePreferences()
        startLocking()
    }

    @Synchronized
    fun removeLock(stream: Int) {
        mVolumeLock.remove(stream)
        savePreferences()
    }

    @Synchronized
    fun getLocks(): HashMap<Int, Int> {
        return mVolumeLock
    }

    fun getMode(): Int {
        return mMode
    }

    private fun savePreferences() {
        val sharedPreferences = getSharedPreferences(APP_SHARED_PREFERENCES, MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString(LOCKS_KEY, Gson().toJson(mVolumeLock))
        editor.apply()
    }

    private fun loadPreferences() {
        val sharedPreferences = getSharedPreferences(APP_SHARED_PREFERENCES, MODE_PRIVATE)
        class Token : TypeToken<HashMap<Int, Int>>()
        val value = sharedPreferences.getString(LOCKS_KEY, "")
        if (!value.isNullOrBlank()) {
            mVolumeLock = Gson().fromJson(value, Token().type)
            startLocking()
        }
    }

    @WorkerThread
    @Synchronized
    private fun checkVolumes() {
        for ((stream, volume) in mVolumeLock) {
            if (mAudioManager.getStreamVolume(stream) != volume) {
                mAudioManager.setStreamVolume(stream, volume, AudioManager.FLAG_SHOW_UI)
            }
        }
    }

    private fun setupNotificationManager() {
        // Commented out: original DND permission check and request removed due to MDM configuration.
        // if (!mNotificationManager.isNotificationPolicyAccessGranted) {
        //     requestNotificationPolicyAccess()
        // }
    }

    private fun requestNotificationPolicyAccess() {
        // Commented out: no need to request permission as it will be granted via MDM.
        // val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        // intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        // startActivity(intent)
    }

    private fun blockDoNotDisturb() {
        try {
            if (mNotificationManager.isNotificationPolicyAccessGranted) {
                mNotificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                Log.d("VolumeService", "Do Not Disturb mode disabled")
            } else {
                // Commented out: request removed as permission is managed by MDM.
                // Log.e("VolumeService", "Notification Policy Access not granted. Requesting access...")
                // requestNotificationPolicyAccess()
            }
        } catch (e: SecurityException) {
            Log.e("VolumeService", "Failed to disable Do Not Disturb mode: ${e.message}")
        } catch (e: Exception) {
            Log.e("VolumeService", "Unexpected error: ${e.message}")
        }
    }

    @Synchronized
    private fun monitorAndBlockDND() {
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    if (mNotificationManager.isNotificationPolicyAccessGranted &&
                        mNotificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                        Log.d("VolumeService", "Detected DND mode. Disabling...")
                        mNotificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                } catch (e: Exception) {
                    Log.e("VolumeService", "Error in monitorAndBlockDND: ${e.message}")
                }
            }
        }, 0, 1000) // Check every second
    }

    private val mVolumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            invokeVolumeListenerCallback()
        }
    }

    private fun invokeVolumeListenerCallback() {
        mVolumeListenerHandler?.removeCallbacksAndMessages(null)
        mVolumeListenerHandler?.post {
            mVolumeListener?.invoke()
        }
    }

    private fun registerObservers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(MODE_RINGER_SETTING), true, mVolumeObserver
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Synchronized
    fun tryShowNotification() {
        if (mVolumeLock.size == 0) {
            return
        }

        createNotificationChannel()
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_DESCRIPTION)
            .setSmallIcon(R.drawable.ic_volumefix_foreground)
            .setContentIntent(createNotificationContentIntent())
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("WrongConstant")
    @RequiresApi(Build.VERSION_CODES.N)
    @Synchronized
    fun tryHideNotification() {
        if (mVolumeLock.size > 0) {
            return
        }

        stopForeground(NOTIFICATION_ID)
    }

    private fun createNotificationContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        return PendingIntent.getActivity(this, NOTIFICATION_ID, intent, flags)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, "volumefix service", NotificationManager.IMPORTANCE_LOW
        )

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    inner class LocalBinder : Binder() {
        fun getService(): VolumeService = this@VolumeService
    }

    override fun onBind(p0: Intent?): IBinder {
        return mBinder
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocking()
    }
}
