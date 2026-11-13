package com.maichimod.prototype

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.maichimod.prototype.session.CallStatus
import com.maichimod.prototype.session.Cancellation

/** Started lifetime owns reception. Binding only grants a UI access to the existing owner. */
class IntercomService : Service() {
    internal lateinit var connection: AppConnection
        private set
    inner class LocalBinder : Binder() { val service: IntercomService get() = this@IntercomService }
    private val binder = LocalBinder()
    private var subscription: Cancellation? = null
    private var foreground = false
    private var microphoneForKey: CallKey? = null
    private var lastNotification: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.reception_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            })
        connection = AppConnection(this)
        subscription = connection.observe(object : IntercomObserver {
            override fun onState(state: IntercomState) {
                if (microphoneForKey != null && (state.callKey != microphoneForKey || !state.inCall || state.microphone == AudioHealth.UNAVAILABLE)) {
                    microphoneForKey = null
                    if (foreground) showForeground(state)
                }
                if (!state.configurationLoaded) return
                if (!state.enabled) {
                    if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
                    foreground = false
                    lastNotification = null
                    stopSelf()
                } else if (foreground) {
                    val text = statusText(state)
                    if (text != lastNotification) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
                        lastNotification = text
                    }
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!showForeground(connection.currentState)) {
            connection.stop()
            connection.reportIssue(getString(R.string.reception_start_failed))
            stopSelf()
            return START_NOT_STICKY
        }
        connection.restore()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder
    // No onUnbind cleanup: calls must arrive with no bound UI.

    internal fun answer(key: CallKey) {
        val state = connection.currentState
        if (state.callKey != key || state.call != CallStatus.RINGING) return
        val capture = state.options.wired && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && prepareMicrophone(key)
        connection.answerCall(key, capture)
    }

    internal fun setReceptionEnabled(enabled: Boolean, onSaved: (Boolean) -> Unit) {
        connection.setReceptionEnabled(enabled) { saved ->
            if (saved && enabled) ensureStarted(this)
            onSaved(saved)
        }
    }

    private fun prepareMicrophone(key: CallKey): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION, notification(statusText(connection.currentState)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }
        microphoneForKey = key
        foreground = true
        true
    } catch (_: RuntimeException) {
        microphoneForKey = null
        connection.reportIssue(getString(R.string.call_microphone_denied))
        false
    }

    private fun showForeground(state: IntercomState): Boolean = try {
        val text = statusText(state)
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (Build.VERSION.SDK_INT >= 30 && microphoneForKey != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        else startForeground(NOTIFICATION, notification)
        foreground = true
        lastNotification = text
        true
    } catch (_: RuntimeException) { false }

    private fun statusText(state: IntercomState): String = state.issue ?: getString(when {
        !state.configurationLoaded -> R.string.reception_restoring
        state.call == CallStatus.RINGING -> R.string.call_ringing
        state.call == CallStatus.ANSWERED -> R.string.call_answered
        !state.enabled -> R.string.reception_stopped
        state.ready -> R.string.reception_listening
        else -> R.string.reception_waiting
    })

    private fun notification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_answer_call)
            .setContentTitle(getString(R.string.app_name)).setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).apply {
                if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }.build()
    }

    override fun onDestroy() {
        subscription?.cancel()
        connection.close()
        microphoneForKey = null
        foreground = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "intercom-reception"
        private const val NOTIFICATION = 100
        internal fun ensureStarted(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, IntercomService::class.java))
            true
        } catch (_: RuntimeException) { false }
    }
}
