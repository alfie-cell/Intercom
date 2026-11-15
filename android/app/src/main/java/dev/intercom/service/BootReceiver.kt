package dev.intercom.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.intercom.data.Config
import dev.intercom.data.Role

/** Brings the receiver back after a reboot or app update, unless the user turned it off. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Config.init(context)
        val s = Config.current
        if (!s.setupDone || s.role != Role.RECEIVER || !s.receiverEnabled) return
        try {
            ReceiverService.start(context)
        } catch (e: Exception) {
            Log.e("IntercomBoot", "could not start receiver after ${intent.action}", e)
        }
    }
}
