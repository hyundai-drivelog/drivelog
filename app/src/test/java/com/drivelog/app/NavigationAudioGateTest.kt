package com.drivelog.app

import com.drivelog.app.core.platform.NavigationAudioGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAudioGateTest {
    private var time = 10_000L
    private val gate = NavigationAudioGate { time }

    @Test fun nonNavigationRecordingCanStillAnnounce() {
        assertTrue(gate.allowsAppAudio())
    }

    @Test fun guidanceSuppressesArrivalAndParkPromptsEvenOnLongDrives() {
        gate.started()
        time += 3_600_000
        assertFalse(gate.allowsAppAudio())
    }

    @Test fun endCallbackDoesNotMeanNavigationHasFinishedSpeaking() {
        gate.started()
        gate.ended()
        assertFalse(gate.allowsAppAudio())
        time += 3_999
        assertFalse(gate.allowsAppAudio())
        time += 1
        assertTrue(gate.allowsAppAudio())
    }

    @Test fun arrivalThenCancellationExtendsTheQuietWindow() {
        gate.ended()
        time += 3_000
        gate.ended()
        time += 1_000
        assertFalse(gate.allowsAppAudio())
        time += 3_000
        assertTrue(gate.allowsAppAudio())
    }

    @Test fun replacementRouteRemainsSilentPastTheOldQuietWindow() {
        gate.ended()
        time += 1_000
        gate.started()
        time += 10_000
        assertFalse(gate.allowsAppAudio())
    }

    @Test fun reconnectDuringGuidanceBlocksSpeechWithoutANewRouteEvent() {
        gate.updateGuidance(true)
        assertFalse(gate.allowsAppAudio())
        gate.updateGuidance(false)
        assertFalse(gate.allowsAppAudio())
        time += NavigationAudioGate.END_QUIET_MS
        gate.updateGuidance(false)
        assertTrue(gate.allowsAppAudio())
    }
}
