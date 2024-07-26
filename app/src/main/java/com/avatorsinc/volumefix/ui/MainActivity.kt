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

        // Set the notification volume to 50%
        audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, fiftyPercentVolume, 0)

        // Start and bind to the VolumeService to lock the volume
        val volumeServiceIntent = Intent(this, VolumeService::class.java)
        startService(volumeServiceIntent)
        bindService(volumeServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Set and lock the notification volume
        setAndLockVolume()
    }

    /*override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkDoNotDisturbPermission()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun checkDoNotDisturbPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                PolicyAccessDialog().show(supportFragmentManager, PolicyAccessDialog.TAG)
            }
        }
    }*/

    private fun setAndLockVolume() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        fiftyPercentVolume = maxVolume / 2
        audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, fiftyPercentVolume, AudioManager.FLAG_SHOW_UI)

        val volumeServiceIntent = Intent(this, VolumeService::class.java)
        startService(volumeServiceIntent)
        bindService(volumeServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

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
}
