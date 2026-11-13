package com.maichimod.prototype

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.maichimod.prototype.session.CallStatus
import com.maichimod.prototype.session.Cancellation

/** A temporary call task. Finishing it reveals the original app or the system keyguard. */
class IncomingCallActivity : Activity() {
    private var service: IntercomService? = null
    private var subscription: Cancellation? = null
    private var bound = false
    private var started = false
    private var resumed = false
    private var key: CallKey? = null
    private var validatedKey: CallKey? = null
    private var state = IntercomState(enabled = false)
    private var requestKey: CallKey? = null
    private var requestRetry = false
    private var microphoneAsked = false
    private var permissionCode = 1000
    private var queuedAudioAction: Pair<CallKey, Boolean>? = null
    private var settingsAction: Pair<CallKey, Boolean>? = null
    private var answerWhenReady = false
    private var deferredIntent: Intent? = null
    private var openedAt = 0L
    private lateinit var preview: ImageView
    private val tick = Runnable { render() }
    private var backCallback: OnBackInvokedCallback? = null
    private val binding = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!started) return
            service = (binder as IntercomService.LocalBinder).service
            service?.refreshAvailability()
            subscription?.cancel()
            subscription = service?.connection?.observe(object : IntercomObserver {
                override fun onState(updated: IntercomState) { state = updated; render() }
                override fun onCallFrame(frame: android.graphics.Bitmap) {
                    if (state.callKey == key && state.inCall) preview.setImageBitmap(frame)
                }
            })
        }
        override fun onServiceDisconnected(name: ComponentName) {
            subscription?.cancel(); subscription = null; service = null
            finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyCallTheme(this, getSystemService(UserManager::class.java).isUserUnlocked)
        super.onCreate(savedInstanceState)
        key = readKey(intent)
        if (key == null) { finishAndRemoveTask(); return }
        answerWhenReady = intent.getBooleanExtra(ANSWER, false)
        microphoneAsked = savedInstanceState?.getBoolean("microphoneAsked") ?: false
        permissionCode = savedInstanceState?.getInt("permissionCode", 1000) ?: 1000
        val savedRequest = savedInstanceState?.getString("requestRun")?.let { CallKey(it, savedInstanceState.getInt("requestSequence")) }
        if (savedRequest != null && savedRequest == key) { requestKey = savedRequest; requestRetry = savedInstanceState?.getBoolean("requestRetry") ?: false }
        openedAt = SystemClock.elapsedRealtime()
        setContentView(R.layout.activity_incoming_call)
        findViewById<View>(R.id.call_page).visibility = View.VISIBLE
        MedioraUi.applyWindowInsets(this, findViewById(R.id.app_root))
        preview = findViewById(R.id.call_preview)
        MedioraUi.applySurface(findViewById(R.id.call_video_frame), SurfaceRole.OUTLINE)
        preview.clipToOutline = true
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        allowLockedPresentation(true)
        findViewById<View>(R.id.call_back).setOnClickListener { finishCall() }
        findViewById<View>(R.id.call_action).setOnClickListener {
            if (state.call == CallStatus.RINGING) requestAudio(false) else if (state.call == CallStatus.ANSWERED) finishCall()
        }
        findViewById<View>(R.id.retry_call_audio).setOnClickListener { requestAudio(true) }
        findViewById<View>(R.id.unlock_call).setOnClickListener {
            val requested = key ?: return@setOnClickListener
            service?.connection?.unlockCall(requested, state.options.autoHangUpAfterUnlock) { result ->
                if (isFinishing || isDestroyed || key != requested) return@unlockCall
                Toast.makeText(this, when (result) {
                    UnlockResult.SENT -> if (state.options.wired) R.string.unlock_sent else R.string.sample_unlock_sent
                    UnlockResult.REJECTED -> R.string.unlock_rejected
                    UnlockResult.UNCONFIRMED -> R.string.unlock_unconfirmed
                }, Toast.LENGTH_LONG).show()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { finishCall() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        if (key == null) return
        started = true
        openedAt = SystemClock.elapsedRealtime()
        IntercomService.ensureStarted(this)
        bound = bindService(Intent(this, IntercomService::class.java), binding, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        service?.refreshAvailability()
        settingsAction?.let { action ->
            settingsAction = null
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) queuedAudioAction = action
        }
        if (::preview.isInitialized) render()
    }

    override fun onPause() { resumed = false; super.onPause() }
    override fun onStop() {
        started = false
        queuedAudioAction = null
        subscription?.cancel(); subscription = null
        if (bound) unbindService(binding)
        bound = false; service = null
        if (::preview.isInitialized) preview.removeCallbacks(tick)
        allowLockedPresentation(false)
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deferredIntent = intent
        openedAt = SystemClock.elapsedRealtime()
        allowLockedPresentation(true)
        if (::preview.isInitialized) render()
    }

    private fun acceptIntent(intent: Intent): Boolean {
        val next = readKey(intent)
        if (next == null || state.callKey != next) {
            if (validatedKey == state.callKey && state.inCall) return true
            finishAndRemoveTask(); return false
        }
        setIntent(intent)
        if (next != key) adopt(next)
        answerWhenReady = answerWhenReady || intent.getBooleanExtra(ANSWER, false)
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("microphoneAsked", microphoneAsked)
        outState.putInt("permissionCode", permissionCode)
        requestKey?.let {
            outState.putString("requestRun", it.runId); outState.putInt("requestSequence", it.sequence)
            outState.putBoolean("requestRetry", requestRetry)
        }
        super.onSaveInstanceState(outState)
    }

    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() { finishCall() }

    private fun adopt(next: CallKey) {
        key = next; validatedKey = null
        requestKey = null; queuedAudioAction = null; settingsAction = null; answerWhenReady = false
        if (::preview.isInitialized) { preview.removeCallbacks(tick); preview.setImageDrawable(null) }
    }

    private fun render() {
        if (!::preview.isInitialized || isFinishing) return
        preview.removeCallbacks(tick)
        if (service == null || !state.configurationLoaded) {
            findViewById<TextView>(R.id.call_state).setText(R.string.reception_restoring)
            disableActions()
            val remaining = openedAt + 5000 - SystemClock.elapsedRealtime()
            if (remaining <= 0) finishAndRemoveTask() else preview.postDelayed(tick, remaining)
            return
        }
        if (state.callKey == key) validatedKey = key
        deferredIntent?.let { intent ->
            deferredIntent = null
            if (!acceptIntent(intent)) return
        }
        if (state.callKey != key) {
            if (validatedKey == key && state.inCall && state.callKey != null) {
                adopt(requireNotNull(state.callKey))
                setIntent(createIntent(this, requireNotNull(key)))
            } else { finishAndRemoveTask(); return }
        }
        validatedKey = key
        if (state.callEnding) { finishAndRemoveTask(); return }
        val now = SystemClock.elapsedRealtime()
        if (!state.inCall) {
            disableActions()
            preview.setImageDrawable(null)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            allowLockedPresentation(false)
            val remaining = state.endSummaryRemainingMs(now)
            if (remaining <= 0) { finishAndRemoveTask(); return }
            findViewById<TextView>(R.id.call_state).text = getString(when (state.callEndReason) {
                CallEndReason.REMOTE -> R.string.call_remote_ended
                CallEndReason.TIMED_OUT -> R.string.call_timed_out
                else -> R.string.call_connection_lost
            })
            findViewById<TextView>(R.id.call_audio_state).text = if (state.unlockResult == UnlockResult.UNCONFIRMED) getString(R.string.unlock_unconfirmed) else ""
            findViewById<View>(R.id.retry_call_audio).visibility = View.GONE
            findViewById<TextView>(R.id.call_video_message).apply { visibility = View.VISIBLE; setText(R.string.call_ended) }
            preview.postDelayed(tick, remaining)
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val title = getString(if (state.call == CallStatus.ANSWERED) R.string.call_answered else R.string.call_ringing)
        val label = if (state.options.wired) title else getString(R.string.call_sample, title)
        findViewById<TextView>(R.id.call_state).text = state.issue?.let { "$label\n$it" } ?: label
        findViewById<TextView>(R.id.call_audio_state).setText(when {
            state.call == CallStatus.RINGING -> if (state.ringerMuted) R.string.ringtone_muted else R.string.call_answer_hint
            state.microphone == AudioHealth.STARTING || state.playback == AudioHealth.STARTING -> R.string.call_audio_connecting
            state.playback == AudioHealth.UNAVAILABLE -> if (state.microphone == AudioHealth.AVAILABLE) R.string.playback_unavailable_microphone_active else R.string.call_audio_unavailable
            state.options.wired && state.microphone == AudioHealth.UNAVAILABLE && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> R.string.microphone_unavailable
            state.options.wired && state.microphone != AudioHealth.AVAILABLE -> R.string.call_microphone_denied
            !state.options.wired -> R.string.sample_call_audio
            else -> R.string.call_audio_ready
        })
        findViewById<View>(R.id.retry_call_audio).apply {
            visibility = if (state.call == CallStatus.ANSWERED && (state.playback == AudioHealth.UNAVAILABLE ||
                (state.options.wired && state.microphone in listOf(AudioHealth.INACTIVE, AudioHealth.UNAVAILABLE)))) View.VISIBLE else View.GONE
            isEnabled = requestKey == null
        }
        findViewById<TextView>(R.id.call_video_message).apply {
            visibility = if (state.video == VideoHealth.AVAILABLE) View.GONE else View.VISIBLE
            setText(when (state.video) {
                VideoHealth.MISSING -> R.string.call_video_missing
                VideoHealth.STALLED -> R.string.call_video_stalled
                else -> R.string.call_video_waiting
            })
        }
        findViewById<Button>(R.id.call_action).apply {
            val answered = state.call == CallStatus.ANSWERED
            val role = if (answered) SurfaceRole.DESTRUCTIVE else SurfaceRole.PRIMARY
            setText(if (answered) R.string.hang_up_call else R.string.answer_call)
            setTextColor(MedioraUi.buttonText(this@IncomingCallActivity, role))
            compoundDrawableTintList = MedioraUi.buttonText(this@IncomingCallActivity, role)
            setCompoundDrawablesWithIntrinsicBounds(0, if (answered) R.drawable.ic_end_call else R.drawable.ic_answer_call, 0, 0)
            MedioraUi.applySurface(this, role, addGutter = false)
            isActivated = !answered && requestKey != null
            isEnabled = answered || requestKey == null
        }
        val cooldown = state.unlockReadyAtMs - now
        findViewById<View>(R.id.unlock_call).apply { isActivated = state.unlockPending; isEnabled = !state.unlockPending && cooldown <= 0 }
        if (cooldown > 0) preview.postDelayed(tick, cooldown)
        if (answerWhenReady && resumed && state.call == CallStatus.RINGING) {
            answerWhenReady = false; requestAudio(false)
        }
        executeAudioAction()
    }

    private fun disableActions() {
        findViewById<View>(R.id.call_action).isEnabled = false
        findViewById<View>(R.id.unlock_call).isEnabled = false
    }

    private fun finishCall() {
        key?.let { requested ->
            val owner = service
            if (owner != null) {
                if (state.callKey == requested && state.inCall) owner.connection.hangUpCall(requested)
            } else if (!IntercomService.requestHangUp(this, requested)) {
                Toast.makeText(this, R.string.hang_up_not_submitted, Toast.LENGTH_LONG).show()
            }
        }
        finishAndRemoveTask()
    }

    @Suppress("DEPRECATION")
    private fun requestAudio(retry: Boolean) {
        val requested = key ?: return
        if (!resumed || state.callKey != requested || !state.inCall || requestKey != null) return
        if (state.options.wired && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (microphoneAsked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                if (retry) openPermissionSettings(requested, true)
                else { queuedAudioAction = requested to false; executeAudioAction() }
                return
            }
            microphoneAsked = true; requestKey = requested; requestRetry = retry
            permissionCode = if (permissionCode >= 65000) 1000 else permissionCode + 1
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionCode)
            render()
        } else { queuedAudioAction = requested to retry; executeAudioAction() }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != permissionCode) return
        val requested = requestKey
        requestKey = null
        if (requested != null && started && requested == key && requested == state.callKey && state.inCall) {
            queuedAudioAction = requested to requestRetry
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) Toast.makeText(this, R.string.call_microphone_denied, Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun executeAudioAction() {
        if (!resumed) return
        val action = queuedAudioAction ?: return
        val owner = service ?: return
        queuedAudioAction = null
        if (action.first != key || action.first != state.callKey || !state.inCall || state.callEnding) return
        if (action.second && state.call == CallStatus.ANSWERED) owner.retryAudio(action.first)
        else if (!action.second && state.call == CallStatus.RINGING) owner.answer(action.first)
    }

    private fun openPermissionSettings(requested: CallKey, retry: Boolean) {
        fun launch() {
            if (isFinishing || key != requested || !state.inCall || !getSystemService(UserManager::class.java).isUserUnlocked) return
            settingsAction = requested to retry
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            catch (_: RuntimeException) { settingsAction = null; Toast.makeText(this, R.string.permission_settings_unavailable, Toast.LENGTH_LONG).show() }
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { launch() }
        }) else launch()
    }

    @Suppress("DEPRECATION")
    private fun allowLockedPresentation(turnOn: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(turnOn) }
        else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            if (turnOn) window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    companion object {
        private const val RUN = "callRun"
        private const val SEQUENCE = "callSequence"
        private const val ANSWER = "answer"
        internal fun readKey(intent: Intent): CallKey? {
            val run = intent.getStringExtra(RUN)?.takeIf { it.isNotBlank() } ?: return null
            return if (intent.hasExtra(SEQUENCE)) CallKey(run, intent.getIntExtra(SEQUENCE, -1)).takeIf { it.sequence >= 0 } else null
        }
        internal fun createIntent(context: Context, key: CallKey, answer: Boolean = false): Intent =
            Intent(context, IncomingCallActivity::class.java).setData(Uri.parse("maichi://call/${key.runId}/${key.sequence}/${if (answer) "answer" else "open"}"))
                .putExtra(RUN, key.runId).putExtra(SEQUENCE, key.sequence).putExtra(ANSWER, answer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        internal fun applyCallTheme(context: Context, userUnlocked: Boolean) { if (userUnlocked) Appearance.apply(context) }
    }
}
