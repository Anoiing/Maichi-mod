package com.maichimod.prototype

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** System boot/update events enter the same idempotent started-service path. */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_USER_UNLOCKED)) return
        val pending = goAsync()
        Thread({
            try { restore(context) } finally { pending?.finish() }
        }, "Maichi-startup").start()
    }

    companion object {
        internal fun restore(context: Context, settings: AppSettings = AppSettings(context)) {
            try {
                settings.load() // Migrates legacy options only when credential storage is available.
                if (settings.isReceptionEnabled() && !IntercomService.ensureStarted(context)) {
                    Log.w("Maichi", "System refused reception startup")
                }
            } catch (failure: RuntimeException) {
                Log.w("Maichi", "Unable to restore reception configuration", failure)
            }
        }
    }
}
