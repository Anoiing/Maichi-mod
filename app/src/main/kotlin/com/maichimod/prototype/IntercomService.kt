package com.maichimod.prototype

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
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
    private var callNoticeKey: CallKey? = null
    private var callNoticeFingerprint: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).apply {
            activeNotifications.filter { it.id == CALL_NOTIFICATION && it.tag?.startsWith("call:") == true }
                .forEach { cancel(it.tag, CALL_NOTIFICATION) }
            createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.reception_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false)
            })
            createNotificationChannel(NotificationChannel(CALL_CHANNEL, getString(R.string.call_notification_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        connection = AppConnection(this)
        subscription = connection.observe(object : IntercomObserver {
            override fun onState(state: IntercomState) {
                // The microphone lease belongs to the answered call, including a user-requested audio retry.
                if (microphoneForKey != null && (state.callKey != microphoneForKey || !state.inCall)) {
                    microphoneForKey = null
                    if (foreground) showForeground(state)
                }
                if (!state.configurationLoaded) return
                if (!state.enabled) {
                    if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
                    foreground = false; lastNotification = null
                    cancelCallNotice()
                    stopSelf()
                } else if (foreground) {
                    if (fingerprint(state) != lastNotification) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, serviceNotification(state))
                        lastNotification = fingerprint(state)
                    }
                    renderCallNotice(state)
                }
            }
        })
        refreshAvailability()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!showForeground(connection.currentState)) {
            connection.failReceptionStart(getString(R.string.reception_start_failed))
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_HANGUP) IncomingCallActivity.readKey(intent)?.let(connection::hangUpCall)
        connection.restore()
        refreshAvailability()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder
    // No onUnbind cleanup: calls must arrive with no bound UI.

    internal fun refreshAvailability() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CALL_CHANNEL)
        val notifications = manager.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
        val fullScreen = notifications && (channel?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH &&
            (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent())
        val audio = getSystemService(AudioManager::class.java)
        connection.updatePresentationCapabilities(notifications, fullScreen,
            audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.getStreamVolume(AudioManager.STREAM_RING) == 0)
    }

    internal fun answer(key: CallKey) {
        val state = connection.currentState
        if (state.callKey != key || state.call != CallStatus.RINGING || state.callEnding) return
        val capture = state.options.wired && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && prepareMicrophone(key)
        connection.answerCall(key, capture)
    }

    internal fun retryAudio(key: CallKey) {
        val state = connection.currentState
        if (state.callKey != key || state.call != CallStatus.ANSWERED || state.callEnding) return
        val capture = state.options.wired && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && prepareMicrophone(key)
        connection.retryCallAudio(key, capture)
    }

    internal fun setReceptionEnabled(enabled: Boolean, onSaved: (Boolean) -> Unit) {
        connection.setReceptionEnabled(enabled) { saved ->
            if (saved && enabled) ensureStarted(this)
            onSaved(saved)
        }
    }

    internal fun retryReception() {
        if (foreground) connection.retry() else ensureStarted(this)
    }

    private fun prepareMicrophone(key: CallKey): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, serviceNotification(connection.currentState),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        microphoneForKey = key; foreground = true
        true
    } catch (_: RuntimeException) {
        microphoneForKey = null
        connection.reportIssue(getString(R.string.call_microphone_denied))
        false
    }

    private fun showForeground(state: IntercomState): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, serviceNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (Build.VERSION.SDK_INT >= 30 && microphoneForKey != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        else startForeground(NOTIFICATION, serviceNotification(state))
        foreground = true; lastNotification = fingerprint(state)
        true
    } catch (_: RuntimeException) { false }

    private fun fingerprint(state: IntercomState) = listOf(state.enabled, state.configurationLoaded,
        state.callKey, state.call, state.callEnding, state.issue, state.notificationsAllowed, state.fullScreenAllowed).joinToString("|")

    private fun statusText(state: IntercomState): String = state.issue ?: getString(when {
        !state.configurationLoaded -> R.string.reception_restoring
        state.callEnding -> R.string.call_ending
        state.call == CallStatus.RINGING -> R.string.call_ringing
        state.call == CallStatus.ANSWERED -> R.string.call_answered
        !state.enabled -> R.string.reception_stopped
        state.ready -> R.string.reception_listening
        else -> R.string.reception_waiting
    })

    private fun serviceNotification(state: IntercomState): Notification {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_answer_call)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .setContentTitle(getString(R.string.app_name)).setContentText(statusText(state))
            .setContentIntent(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).apply {
                if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }.build()
    }

    private fun renderCallNotice(state: IntercomState) {
        val key = state.callKey
        if (!state.inCall || state.callEnding || key == null) { cancelCallNotice(); return }
        if (key != callNoticeKey) cancelCallNotice()
        if (callNoticeFingerprint == fingerprint(state) && callNoticeKey == key) return
        getSystemService(NotificationManager::class.java).notify(callTag(key), CALL_NOTIFICATION, callNotification(state, key))
        callNoticeKey = key; callNoticeFingerprint = fingerprint(state)
    }

    private fun cancelCallNotice() {
        callNoticeKey?.let { getSystemService(NotificationManager::class.java).cancel(callTag(it), CALL_NOTIFICATION) }
        callNoticeKey = null; callNoticeFingerprint = null
    }
    private fun callTag(key: CallKey) = "call:${key.runId}:${key.sequence}"

    private fun callNotification(state: IntercomState, key: CallKey): Notification {
        val open = callPendingIntent(key, answer = false)
        val answer = callPendingIntent(key, answer = true)
        val end = PendingIntent.getForegroundService(this, 0,
            hangUpIntent(this, key), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val name = getString(R.string.unit_door_call).let { if (state.options.wired) it else getString(R.string.call_sample, it) }
        val builder = Notification.Builder(this, CALL_CHANNEL).setSmallIcon(R.drawable.ic_answer_call)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setContentTitle(name).setContentText(statusText(state)).setContentIntent(open)
        if (state.call == CallStatus.RINGING) builder.addAction(Notification.Action.Builder(null, getString(R.string.answer_call), answer).build())
        builder.addAction(Notification.Action.Builder(null, getString(R.string.hang_up_call), end).build())
        val manager = getSystemService(NotificationManager::class.java)
        val fullScreen = manager.areNotificationsEnabled() && (manager.getNotificationChannel(CALL_CHANNEL)?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH &&
            (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent())
        if (state.call == CallStatus.RINGING && fullScreen) builder.setFullScreenIntent(open, true)
        return builder.build()
    }

    private fun callPendingIntent(key: CallKey, answer: Boolean): PendingIntent {
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= 36) setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
            else if (Build.VERSION.SDK_INT >= 35) setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        return PendingIntent.getActivity(this, 0, IncomingCallActivity.createIntent(this, key, answer),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options.toBundle())
    }

    override fun onDestroy() {
        subscription?.cancel(); cancelCallNotice(); connection.close()
        microphoneForKey = null; foreground = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "intercom-reception"
        internal const val CALL_CHANNEL = "intercom-calls"
        private const val NOTIFICATION = 100
        private const val CALL_NOTIFICATION = 101
        private const val ACTION_HANGUP = "com.maichimod.prototype.HANG_UP"
        internal fun hangUpIntent(context: Context, key: CallKey): Intent = IncomingCallActivity.createIntent(context, key)
            .setClass(context, IntercomService::class.java).setFlags(0).setAction(ACTION_HANGUP)
        internal fun requestHangUp(context: Context, key: CallKey): Boolean = try {
            context.startForegroundService(hangUpIntent(context, key)); true
        } catch (_: RuntimeException) { false }
        internal fun ensureStarted(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, IntercomService::class.java)); true
        } catch (_: RuntimeException) { false }
    }
}
