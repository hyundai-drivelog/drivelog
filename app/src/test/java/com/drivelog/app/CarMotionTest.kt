package com.drivelog.app

import androidx.compose.ui.geometry.Offset
import com.drivelog.app.core.platform.RouteEnd
import com.drivelog.app.feature.car.RouteEndAction
import com.drivelog.app.feature.car.Screen
import com.drivelog.app.feature.car.routeDepth
import com.drivelog.app.feature.car.routeEndAction
import com.drivelog.app.feature.car.routeFor
import com.drivelog.app.feature.car.trimLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMotionTest {
    @Test fun movingOnlyEverShowsTheRecordingOrLockScreen() {
        Screen.entries.forEach { screen ->
            assertEquals(Screen.LOCKED, routeFor(recording = false, parked = false, screen))
            assertEquals(Screen.RECORDING, routeFor(recording = true, parked = false, screen))
        }
    }

    @Test fun parkedFollowsNavigationUnlessRecording() {
        assertEquals(Screen.HISTORY, routeFor(recording = false, parked = true, Screen.HISTORY))
        assertEquals(Screen.STOP, routeFor(recording = true, parked = true, Screen.STOP))
        assertEquals(Screen.RECORDING, routeFor(recording = true, parked = true, Screen.HISTORY))
    }

    @Test fun theDriveFlowAlwaysMovesForward() {
        val flow = listOf(Screen.HOME, Screen.PLACE, Screen.RECORDING, Screen.STOP, Screen.DONE)
        flow.zipWithNext().forEach { (from, to) -> assertTrue("$from to $to", routeDepth(to) > routeDepth(from)) }
    }

    @Test fun guidanceEndingSavesOnlyOnceParked() {
        RouteEnd.entries.forEach { end ->
            assertEquals(RouteEndAction.ANNOUNCE, routeEndAction(end, recording = true, parked = false))
            assertEquals(RouteEndAction.SAVE, routeEndAction(end, recording = true, parked = true))
            assertEquals(RouteEndAction.NONE, routeEndAction(end, recording = false, parked = true))
        }
    }

    @Test fun guidanceStillRunningLeavesTheRecordingAlone() {
        assertEquals(RouteEndAction.NONE, routeEndAction(null, recording = true, parked = true))
        assertEquals(RouteEndAction.NONE, routeEndAction(null, recording = true, parked = false))
    }

    private val lines = listOf(
        listOf(Offset(0f, 0f), Offset(10f, 0f)),
        listOf(Offset(100f, 100f), Offset(100f, 130f)),
    )

    @Test fun fullFractionKeepsTheOriginalLines() = assertSame(lines, trimLines(lines, 1f))

    @Test fun zeroFractionHasOnlyTheStart() {
        val line = trimLines(lines, 0f).single()
        assertEquals(Offset(0f, 0f), line.first())
        assertEquals(Offset(0f, 0f), line.last())
    }

    @Test fun trimmingSpansLinesAndSkipsTheGapBetweenThem() {
        // 40 long in total, so half is the first line plus 10 along the second. The 141 long jump does not count.
        val trimmed = trimLines(lines, 0.5f)
        assertEquals(2, trimmed.size)
        assertEquals(lines[0], trimmed[0])
        assertEquals(Offset(100f, 110f), trimmed[1].last())
    }

    @Test fun nothingToTrimStaysEmpty() = assertEquals(emptyList<List<Offset>>(), trimLines(emptyList(), 0.5f))
}
