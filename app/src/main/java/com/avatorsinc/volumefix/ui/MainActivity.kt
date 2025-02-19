package com.avatorsinc.volumefix.ui

import android.app.AlertDialog
import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import com.avatorsinc.volumefix.R
import com.avatorsinc.volumefix.service.VolumeService

class MainActivity : AppCompatActivity() {

    private var fiftyPercentVolume = 0
    private var volumeService: VolumeService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get the AudioManager
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Calculate 50% of the maximum notification volume
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        fiftyPercentVolume = maxVolume / 2

        // Since permission will be granted via MDM, we simply set and lock the volume.
        setAndLockVolume()

        // Start and bind to the VolumeService to lock the volume
        val volumeServiceIntent = Intent(this, VolumeService::class.java)
        startService(volumeServiceIntent)
        bindService(volumeServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // Commented out the DND permission check and dialog
    // @RequiresApi(Build.VERSION_CODES.M)
    // private fun checkDoNotDisturbPermission() {
    //     val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    //     if (!notificationManager.isNotificationPolicyAccessGranted) {
    //         // Show a dialog to request the permission
    //         PolicyAccessDialog().show(supportFragmentManager, PolicyAccessDialog.TAG)
    //     } else {
    //         // Permission is granted; set and lock the volume
    //         setAndLockVolume()
    //     }
    // }

    private fun setAndLockVolume() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, fiftyPercentVolume, AudioManager.FLAG_SHOW_UI)

            volumeService?.addLock(AudioManager.STREAM_NOTIFICATION, fiftyPercentVolume)
            Log.d("MainActivity", "Volume locked to 50%")
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Failed to set volume: ${e.message}")
        }
    }

    // PolicyAccessDialog remains in the code, but it is now unused.
    class PolicyAccessDialog : DialogFragment() {
        companion object {
            const val TAG = "PolicyAccessDialog"
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val context = requireContext()
            return AlertDialog.Builder(context)
                .setMessage(context.getString(R.string.dialog_policy_access_title))
                .setCancelable(false)
                .setPositiveButton(context.getString(R.string.dialog_allow)) { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                }
                .setNegativeButton(context.getString(R.string.dialog_deny)) { _, _ ->
                    Log.e(TAG, "User denied Notification Policy Access")
                }
                .create()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VolumeService.LocalBinder
            volumeService = binder.getService()
            volumeService?.addLock(AudioManager.STREAM_NOTIFICATION, fiftyPercentVolume)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            volumeService = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
        volumeService = null
    }
}
