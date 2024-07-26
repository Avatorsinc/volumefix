package com.avatorsinc.volumefix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.avatorsinc.volumefix.service.VolumeService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val service = Intent(context, VolumeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        }
    }
}
