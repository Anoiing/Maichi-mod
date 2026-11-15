package com.maichimod.prototype

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor

/** Main-thread owner of one camera analysis session; stop is reversible, close is final. */
internal class PresenceDetector(
    context: Context,
    private val camera: PresenceCamera = LocalPresenceCamera(context.applicationContext),
) : AutoCloseable {
    enum class Status { STOPPED, STARTING, RUNNING, UNAVAILABLE }
    var status = Status.STOPPED
        private set
    var intervalMs: Long = 200
        set(value) { field = value.coerceAtLeast(200) }
    private var generation = 0L
    private var closed = false
    private var processingFor: Long? = null
    private var lastAnalysisAtMs: Long? = null
    private var failures = 0
    private var callback: ((Presence, Long) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var watchdog: Runnable? = null

    private fun armWatchdog() {
        watchdog?.let(handler::removeCallbacks)
        val session = generation
        watchdog = Runnable {
            if (session == generation && callback != null) unavailable(SystemClock.elapsedRealtime())
        }.also { handler.postDelayed(it, 10000) }
    }

    fun start(onPresence: (Presence, Long) -> Unit) {
        if (closed || status != Status.STOPPED) return
        val session = ++generation
        callback = onPresence
        failures = 0
        lastAnalysisAtMs = null
        status = Status.STARTING
        armWatchdog()
        try {
            camera.start({ frame ->
                val previous = lastAnalysisAtMs
                if (session != generation || callback == null || processingFor == session ||
                    (previous != null && frame.capturedAtMs - previous < intervalMs)) {
                    frame.close()
                } else {
                    processingFor = session
                    lastAnalysisAtMs = frame.capturedAtMs
                    var finished = false
                    val complete: (Presence?) -> Unit = complete@{ result ->
                        if (finished) return@complete
                        finished = true
                        frame.close()
                        if (processingFor == session) processingFor = null
                        if (session != generation || callback == null) return@complete
                        if (result == null) {
                            failures++
                            if (failures >= 3) unavailable(frame.capturedAtMs)
                            else { armWatchdog(); callback?.invoke(Presence.UNKNOWN, frame.capturedAtMs) }
                        } else {
                            failures = 0
                            status = Status.RUNNING
                            armWatchdog()
                            callback?.invoke(result, frame.capturedAtMs)
                        }
                    }
                    try { frame.detect(complete) }
                    catch (failure: RuntimeException) {
                        Log.w("Maichi-presence", "Unable to analyze camera frame", failure)
                        complete(null)
                    }
                }
            }, { if (generation == session) unavailable(SystemClock.elapsedRealtime()) })
        } catch (failure: RuntimeException) {
            Log.w("Maichi-presence", "Unable to start camera", failure)
            unavailable(SystemClock.elapsedRealtime())
        }
    }

    private fun unavailable(atMs: Long) {
        val observer = callback ?: return
        generation++
        callback = null
        watchdog?.let(handler::removeCallbacks); watchdog = null
        processingFor = null
        status = Status.UNAVAILABLE
        camera.stop()
        observer(Presence.UNKNOWN, atMs)
    }

    fun stop() {
        generation++
        callback = null
        watchdog?.let(handler::removeCallbacks); watchdog = null
        processingFor = null
        camera.stop()
        status = Status.STOPPED
        lastAnalysisAtMs = null
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        camera.close()
    }
}

/** The boundary keeps device images out of the policy and lets failures/late frames be exercised. */
internal interface PresenceCamera : AutoCloseable {
    fun start(onFrame: (PresenceFrame) -> Unit, onFailure: () -> Unit)
    fun stop()
}

internal interface PresenceFrame : AutoCloseable {
    val capturedAtMs: Long
    fun detect(onResult: (Presence?) -> Unit)
}

/** No preview, recording, file output, frame upload or dynamically downloaded face model. */
private class LocalPresenceCamera(private val context: Context) : PresenceCamera, LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override fun getLifecycle(): Lifecycle = registry
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var model: FaceDetector? = null
    private var generation = 0L
    private var closed = false

    init { registry.currentState = Lifecycle.State.CREATED }

    override fun start(onFrame: (PresenceFrame) -> Unit, onFailure: () -> Unit) {
        if (closed) return
        val session = ++generation
        registry.currentState = Lifecycle.State.STARTED
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed || generation != session) return@addListener
            try {
                val owner = future.get()
                provider = owner
                if (!owner.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) { onFailure(); return@addListener }
                if (model == null) model = FaceDetection.getClient(FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .build())
                val useCase = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis = useCase
                useCase.setAnalyzer(executor) { image ->
                    if (closed || generation != session) image.close()
                    else onFrame(frame(image))
                }
                val bound = owner.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, useCase)
                camera = bound
                bound.cameraInfo.cameraState.observe(this) { state ->
                    if (generation == session && state.error != null) onFailure()
                }
            } catch (failure: Exception) {
                Log.w("Maichi-presence", "Camera unavailable", failure)
                if (generation == session) onFailure()
            }
        }, executor)
    }

    private fun frame(proxy: ImageProxy): PresenceFrame = object : PresenceFrame {
        override val capturedAtMs = SystemClock.elapsedRealtime()
        private var released = false
        override fun detect(onResult: (Presence?) -> Unit) {
            val detector = model
            if (detector == null) { onResult(null); return }
            val rotation = proxy.imageInfo.rotationDegrees
            // Own the pixels before asynchronous inference, so a stalled old task cannot hold the camera open.
            val bitmap = try { proxy.toBitmap() } finally { close() }
            try {
                detector.process(InputImage.fromBitmap(bitmap, rotation)).addOnCompleteListener(executor) { result ->
                    bitmap.recycle()
                    val presence = if (!result.isSuccessful) null else if (result.result.isEmpty()) Presence.ABSENT else Presence.PRESENT
                    onResult(presence)
                }
            } catch (failure: RuntimeException) {
                bitmap.recycle()
                throw failure
            }
        }
        override fun close() { if (!released) { released = true; proxy.close() } }
    }

    override fun stop() {
        generation++
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        camera = null
        analysis?.let { useCase ->
            useCase.clearAnalyzer()
            provider?.unbind(useCase)
        }
        analysis = null
        // Each restart gets a new client; obsolete inference cannot serialize the new camera session.
        model?.close(); model = null
        if (!closed) registry.currentState = Lifecycle.State.CREATED
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
