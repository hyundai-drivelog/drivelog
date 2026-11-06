package com.drivelog.app.feature.car

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * State feedback built on the Pleos components that have states: the Basic and Toggle buttons (Pressed, Disabled,
 * Loading, Toggled) and the Toast. The guide gives no durations or curves, so these are the app's own, kept short.
 * Every effect asks LocalMotionEnabled and runs once; with motion off it snaps to its end.
 */

/** A change of state: a colour, a label, a loader in a button. */
const val MOTION_STATE_MS = 160

/** Something arriving on screen. */
const val MOTION_ENTER_MS = 280

/** A tween while parked, nothing at all while moving. */
@Composable
fun <T> motionTween(durationMillis: Int = MOTION_STATE_MS, delayMillis: Int = 0): FiniteAnimationSpec<T> =
    if (LocalMotionEnabled.current) tween(durationMillis, delayMillis, FastOutSlowInEasing) else snap()

/**
 * Fades up into place the first time it is composed for [key]. For controls that become available with a new
 * state, such as the end-of-drive buttons appearing when the car reaches P. Not saved: a new state plays again.
 */
@Composable
fun Modifier.appear(key: Any? = Unit, delayMillis: Int = 0): Modifier {
    val progress = rememberProgress(key, tween(MOTION_ENTER_MS, delayMillis, FastOutSlowInEasing))
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 12.dp.toPx()
    }
}

/**
 * A rubber stamp coming down: large and turned, then pressed flat at its own tilt with a small bounce. Plays once
 * for [key], only where a stamp was just earned, after the screen has settled.
 */
@Composable
fun Modifier.stampPress(key: Any?, enabled: Boolean, delayMillis: Int = 420): Modifier {
    val motion = LocalMotionEnabled.current && enabled
    val progress = remember(key) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(progress) {
        if (progress.value >= 1f) return@LaunchedEffect
        delay(delayMillis.toLong())
        progress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    return graphicsLayer {
        val p = progress.value
        val scale = 1.7f - 0.7f * p
        scaleX = scale
        scaleY = scale
        alpha = p.coerceIn(0f, 1f)
        rotationZ = (1f - p) * 18f
    }
}

/** One short message. A new id shows it again even with the same words, as the driver did the thing again. */
data class CarToast(val text: String, val id: Long = System.nanoTime())

private const val TOAST_SHOWN_MS = 2_600L

/**
 * Pleos's Toast: at the foot of the app panel, 24 in from the sides and bottom, at most 792 wide with the message
 * within 712. It says what just happened and leaves by itself. CarApp draws it only while parked.
 */
@Composable
fun BoxScope.ToastHost(toast: CarToast?, onShown: (CarToast) -> Unit) {
    val d = LocalCarDimens.current
    var shown by remember { mutableStateOf<CarToast?>(null) }
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(toast?.id) {
        if (toast == null) return@LaunchedEffect
        shown = toast
        visible.targetState = true
        delay(TOAST_SHOWN_MS)
        visible.targetState = false
        onShown(toast)
    }
    val motion = LocalMotionEnabled.current
    AnimatedVisibility(
        visible, Modifier.align(Alignment.BottomCenter).padding(start = d.u(24), end = d.u(24), bottom = d.u(24)),
        enter = if (motion) fadeIn(tween(MOTION_ENTER_MS)) + slideInVertically(tween(MOTION_ENTER_MS, easing = FastOutSlowInEasing)) { it / 2 } else fadeIn(snap()),
        exit = if (motion) fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 3 } else fadeOut(snap()),
    ) {
        val colors = MaterialTheme.colorScheme
        Row(
            Modifier.widthIn(max = 792.dp).clip(RoundedCornerShape(d.u(16))).background(colors.primary)
                .padding(horizontal = d.u(28), vertical = d.u(18)).semantics { liveRegion = LiveRegionMode.Polite }.testTag("toast"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CarIcon(CarGlyph.CHECK, d.u(28), colors.onPrimary)
            Spacer(Modifier.width(d.u(12)))
            T(shown?.text.orEmpty(), d.t(20), Modifier.widthIn(max = 712.dp), color = colors.onPrimary, bold = true, maxLines = 2)
        }
    }
}
