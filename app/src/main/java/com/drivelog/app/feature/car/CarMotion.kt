package com.drivelog.app.feature.car

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Pleos guide uses animation "to explain state", and forbids it while driving. So every effect here marks
 * a change of state rather than decorating, and asks this first. It defaults to off, and CarApp turns it on
 * only while parked: leaving P stops everything at once.
 */
val LocalMotionEnabled = staticCompositionLocalOf { false }

private const val LOOP_UPDATE_MS = 50L

/** How far along the drive flow a screen sits. Moving to a higher one slides forward, to a lower one back. */
fun routeDepth(route: Screen): Int = when (route) {
    Screen.HOME, Screen.LOCKED, Screen.RECOVERY -> 0
    Screen.PLACE, Screen.HISTORY, Screen.DIAGNOSTICS -> 1
    Screen.RECORDING, Screen.RECORD -> 2
    Screen.STOP -> 3
    Screen.DONE, Screen.SYNCING, Screen.LINK, Screen.OFFLINE, Screen.PHONE_PREVIEW_LINK -> 4
    Screen.PHONE_PREVIEW_DONE -> 5
}

fun screenTransition(from: Screen, to: Screen): ContentTransform {
    val direction = if (routeDepth(to) >= routeDepth(from)) 1 else -1
    val slide = tween<IntOffset>(240, easing = FastOutSlowInEasing)
    return ContentTransform(
        fadeIn(tween(180, delayMillis = 40)) + slideInHorizontally(slide) { direction * it / 28 },
        fadeOut(tween(90)) + slideOutHorizontally(slide) { -direction * it / 28 },
        sizeTransform = null,
    )
}

/** Sits on a screen that is leaving, so a second tap cannot act on something already on its way out. */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

/** A 0 to 1 value that runs once per [key], or stays at 1 when motion is off. */
@Composable
fun rememberProgress(key: Any?, spec: AnimationSpec<Float> = tween(800, easing = FastOutSlowInEasing), enabled: Boolean = true): State<Float> {
    val motion = LocalMotionEnabled.current && enabled
    val progress = remember(key) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(progress) { progress.animateTo(1f, spec) }
    return progress.asState()
}

/**
 * The guide's Pressed state is a change of fill, not of size: 5% of the ink at rest and 10% under the finger.
 * This adds the difference over whatever a control already draws, in place of the default ripple.
 */
data class PressOverlay(val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressOverlayNode(interactionSource, color)
}

private class PressOverlayNode(private val source: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) drawRect(color)
    }
}

/** A tap with the touch feedback the guide asks for: the Pressed state, and a haptic where the vehicle has one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressClick(
    role: Role = Role.Button, enabled: Boolean = true, source: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current, onClick: () -> Unit,
): Modifier {
    val haptic = LocalHapticFeedback.current
    return combinedClickable(source, indication, enabled = enabled, role = role) {
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        onClick()
    }
}

/** The guide's two loaders: LINEAR for a short wait, TENSION where loading takes somewhat longer. */
enum class LoadingStyle { LINEAR, TENSION }

/**
 * Pleos's Loading animation, drawn from its published Lottie files rather than adding a Lottie runtime for one
 * shape. On their 64 unit canvas: a ring of radius 24 and width 8 at 10%, and a round-capped arc at 84% turning
 * three times every 2 s. LINEAR keeps the arc at 28% of the ring; TENSION grows it from 1% to 60% and back.
 */
@Composable
fun LoadingIndicator(style: LoadingStyle, size: Dp = 64.dp, color: Color = fg(), modifier: Modifier = Modifier) {
    val motion = LocalMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "loading")
    val turning by transition.animateFloat(0f, 1080f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "loading turn")
    val breathing by transition.animateFloat(3.6f, 216f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse), label = "loading sweep")
    Canvas(modifier.size(size).semantics { contentDescription = "불러오는 중" }) {
        val unit = this.size.width / 64f
        drawCircle(color.copy(alpha = 0.1f), 24f * unit, style = Stroke(8f * unit))
        val sweep = if (style == LoadingStyle.TENSION && motion) breathing else 100.8f
        drawArc(
            color.copy(alpha = 0.84f), -90f + (if (motion) turning else 0f), sweep, false, Offset(8f * unit, 8f * unit), Size(48f * unit, 48f * unit),
            style = Stroke(8f * unit, cap = StrokeCap.Round),
        )
    }
}

/** Marks something live, such as recording or listening. Holds still when motion is off. */
@Composable
fun PulsingDot(size: Dp, color: Color, modifier: Modifier = Modifier) {
    val pulse = if (LocalMotionEnabled.current) {
        val phase by rememberLoopPhase(1700)
        Modifier.graphicsLayer {
            val rising = phase * 2f
            alpha = 1f - 0.7f * (if (rising > 1f) 2f - rising else rising)
        }
    } else Modifier
    Box(modifier.then(pulse).size(size).clip(CircleShape).background(color))
}

/**
 * A repeating 0 to 1 phase for effects that run as long as a screen is open. The guide caps a parked app's CPU
 * and 60 updates a second used too much of it (38% of one core for one dot on the emulator), so this is slower.
 * Waiting for a frame also stops it in the background, and it holds still when system animations are off.
 */
@Composable
fun rememberLoopPhase(periodMillis: Int): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMillis) {
        if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            delay(LOOP_UPDATE_MS)
            withFrameMillis { now -> phase.floatValue = ((now - start) % periodMillis).toFloat() / periodMillis }
        }
    }
    return phase
}
