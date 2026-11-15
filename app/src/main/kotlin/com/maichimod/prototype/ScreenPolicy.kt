package com.maichimod.prototype

internal enum class Presence { PRESENT, ABSENT, UNKNOWN }

internal data class StandbyOptions(
    val enabled: Boolean = false,
    val presenceEnabled: Boolean = false,
    val valid: Boolean = true,
)

internal data class ScreenEnvironment(
    val visible: Boolean = false,
    val interactive: Boolean = true,
    val unlocked: Boolean = true,
    val engaged: Boolean = false,
    val cameraAllowed: Boolean = false,
    val externalPower: Boolean = true,
    val batteryPercent: Int = 100,
    val hour: Int = 12,
)

internal data class ScreenDemand(
    val brightness: Float = -1f,
    val keepScreenOn: Boolean = false,
    val dimmed: Boolean = false,
    val detectionIntervalMs: Long? = null,
    val nextCheckAtMs: Long? = null,
)

/** Only the visible app window is controlled here. No reception, navigation or device wake actions. */
internal class ScreenPolicy(nowMs: Long) {
    private var options = StandbyOptions()
    private var environment = ScreenEnvironment()
    private var lastActivityMs = nowMs
    private var lastInteractionMs = nowMs
    private var lastSampleMs: Long? = null
    private var presentSinceMs: Long? = null
    private var confirmedPresent = false
    var presence = Presence.UNKNOWN
        private set
    var sensingGeneration = 0L
        private set

    private val visible get() = environment.visible && environment.interactive && environment.unlocked
    private val canDetect get() = visible && options.enabled && options.presenceEnabled &&
        options.valid && environment.cameraAllowed && environment.batteryPercent > 10 && !environment.engaged

    fun update(nowMs: Long, options: StandbyOptions, environment: ScreenEnvironment): ScreenDemand {
        val wasVisible = visible
        val wasDetecting = canDetect
        val restartIdle = this.options != options || (!wasVisible && environment.visible && environment.interactive && environment.unlocked) ||
            (this.environment.engaged && !environment.engaged)
        this.options = options
        this.environment = environment
        if (restartIdle) interaction(nowMs)
        if (wasDetecting != canDetect || wasVisible != visible) {
            sensingGeneration++
            clearPresence()
        }
        return demand(nowMs)
    }

    fun interaction(nowMs: Long) {
        lastInteractionMs = nowMs
        lastActivityMs = nowMs
        clearPresence()
    }

    fun onPresence(result: Presence, capturedAtMs: Long, generation: Long) {
        if (!canDetect || generation != sensingGeneration || capturedAtMs < lastInteractionMs ||
            lastSampleMs?.let { capturedAtMs <= it } == true) return
        // A delayed single result is not evidence of continuously seeing a person.
        if (lastSampleMs?.let { capturedAtMs - it > 2500 } == true) clearPresence()
        lastSampleMs = capturedAtMs
        presence = result
        if (result == Presence.PRESENT) {
            if (presentSinceMs == null) presentSinceMs = capturedAtMs
            if (capturedAtMs - presentSinceMs!! >= 800) confirmedPresent = true
            if (confirmedPresent) lastActivityMs = maxOf(lastActivityMs, capturedAtMs)
        } else {
            presentSinceMs = null
            confirmedPresent = false
        }
    }

    fun demand(nowMs: Long): ScreenDemand {
        if (!visible) return ScreenDemand()
        if (environment.engaged) return ScreenDemand(keepScreenOn = true)
        if (!options.enabled || !options.valid) return ScreenDemand()
        if (lastSampleMs?.let { nowMs - it >= 5000 } == true) clearPresence()
        val deadline = lastActivityMs + 30000
        val dimmed = !confirmedPresent && nowMs >= deadline
        val slow = dimmed || !environment.externalPower || environment.hour >= 22 || environment.hour < 6
        val staleAt = lastSampleMs?.plus(5000)
        val next = listOfNotNull(deadline.takeIf { it > nowMs }, staleAt?.takeIf { it > nowMs }).minOrNull()
        return ScreenDemand(
            brightness = if (dimmed) 0.05f else -1f,
            keepScreenOn = environment.batteryPercent > 10,
            dimmed = dimmed,
            detectionIntervalMs = if (canDetect) if (slow) 2000 else 200 else null,
            nextCheckAtMs = next,
        )
    }

    private fun clearPresence() {
        presence = Presence.UNKNOWN
        lastSampleMs = null
        presentSinceMs = null
        confirmedPresent = false
    }
}
