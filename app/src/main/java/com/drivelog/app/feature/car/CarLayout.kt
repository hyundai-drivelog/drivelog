package com.drivelog.app.feature.car

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class CarLayoutMode { WIDE, STACKED }

/**
 * Layout decisions come from the window the system actually grants, not the panel's inches. Pleos draws its
 * own status bar, dock and cluster pane, and the driver can resize the app or share the screen with another,
 * so one vehicle produces many window shapes:
 * - WIDE: full app area on 12.9", 14.6" and 17" panels (mockups 01~11)
 * - STACKED: the portrait half of a split screen (mockup 12)
 * Sizes are written in mockup units and scaled to fit, keeping proportions across panels.
 */
@Immutable
data class CarDimens(val mode: CarLayoutMode, val scale: Float) {
    val stacked get() = mode == CarLayoutMode.STACKED
    fun u(value: Float): Dp = (value * scale).dp
    fun u(value: Int): Dp = (value * scale).dp
    fun t(value: Int): TextUnit = maxOf(value * scale, MIN_TEXT_SP).sp
}

/** Pleos's smallest System UI size. A small window shrinks the layout, never the text below this. */
const val MIN_TEXT_SP = 12f

const val WIDE_MIN_ASPECT = 1.15f

private const val BARELY_MOVED_KM = 0.1

/** Under about one block, or no route at all: likely a start pressed by mistake, so saving becomes a choice. */
fun barelyMoved(distanceKm: Double?, hasRoute: Boolean) = !hasRoute || distanceKm == null || distanceKm < BARELY_MOVED_KM

fun carDimensFor(widthDp: Float, heightDp: Float): CarDimens {
    val mode = if (widthDp / heightDp >= WIDE_MIN_ASPECT) CarLayoutMode.WIDE else CarLayoutMode.STACKED
    val scale = if (mode == CarLayoutMode.WIDE) minOf(widthDp / 1100f, heightDp / 600f) else minOf(widthDp / 640f, heightDp / 900f)
    return CarDimens(mode, scale.coerceIn(0.6f, 2.4f))
}

val LocalCarDimens = staticCompositionLocalOf { CarDimens(CarLayoutMode.WIDE, 1f) }
