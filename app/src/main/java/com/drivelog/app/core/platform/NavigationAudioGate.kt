package com.drivelog.app.core.platform

/** Navigation owns arrival/end speech. Drop conflicting app prompts instead of replaying them late. */
class NavigationAudioGate(private val now: () -> Long) {
    private var guiding = false
    private var quietUntil = Long.MIN_VALUE

    fun started() { guiding = true }

    /** Reconnecting to navigation must also recognise guidance that began before this app started. */
    fun updateGuidance(active: Boolean) {
        if (active) started() else if (guiding) ended()
    }

    fun ended() {
        guiding = false
        quietUntil = now() + END_QUIET_MS
    }

    fun allowsAppAudio(): Boolean = !guiding && now() >= quietUntil

    companion object {
        const val END_QUIET_MS = 4_000L
    }
}
