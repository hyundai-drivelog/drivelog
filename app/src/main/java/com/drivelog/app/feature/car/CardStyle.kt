package com.drivelog.app.feature.car

import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.drivelog.app.core.journey.RecordV

/*
 * One card style for the car, the phone card (web/card) and the phone app (server static/m/card.js), set after
 * benchmarking share cards: one big number, the route on open space, white in three strengths, the brand small in a
 * corner. What is ours is the stamp: a drive that ended at a stamp place carries a postage stamp in the top right.
 */

/** Eyebrow and title on the left; the stamp this drive earned, if any, pressed in the top right. */
@Composable
internal fun CardHeading(record: RecordV?, eyebrow: String, titleSize: Int, eyebrowColor: Color = DriveCardMuted, modifier: Modifier = Modifier, pressStamp: Boolean = false) {
    val d = LocalCarDimens.current
    val stamp = remember(record?.id) { record?.let { stampPlaceFor(it) } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            // Never under 16: the guide's 3 mm for secondary text at the car display's distance.
            T(eyebrow, d.t(maxOf(16, titleSize / 2)), Modifier.testTag("poster-title"), color = eyebrowColor, maxLines = 1, bold = true)
            Spacer(Modifier.height(d.u(2)))
            if (record != null) T("${shortName(record.placeName)}에 남긴 하루", d.t(titleSize), color = DriveCardText, maxLines = 1, display = true)
        }
        if (stamp != null) {
            Spacer(Modifier.width(d.u(8)))
            CardStamp(stamp, d.u(titleSize * 2), Modifier.stampPress(stamp.id, enabled = pressStamp))
        } else if (record?.simulated == true) {
            // In the card, as the phone card has it, so any crop of the card still says what it is.
            Spacer(Modifier.width(d.u(8)))
            SimulationMark()
        }
    }
}

/** The outlined mark a simulated drive carries where a stamp would be. Outlined, because it is not a button. */
@Composable
internal fun SimulationMark() {
    val d = LocalCarDimens.current
    T(
        "시뮬레이션", d.t(16),
        Modifier.border(1.dp, DriveCardMuted, RoundedCornerShape(16.dp)).padding(horizontal = d.u(12), vertical = d.u(4)).testTag("card-sim-mark"),
        color = DriveCardText, bold = true, maxLines = 1,
    )
}

/**
 * A postage stamp: white perforated paper around the place's own stamp drawing, tilted as the stamp board tilts it.
 * Only ever for a real drive that ended there, which [stampPlaceFor] already decides.
 */
@Composable
internal fun CardStamp(place: StampPlace, width: Dp, modifier: Modifier = Modifier) {
    val height = width * 1.17f
    Box(
        modifier.size(width, height).rotate(place.tilt).testTag("card-stamp")
            .semantics { contentDescription = place.name + " 스탬프" },
        contentAlignment = Alignment.Center,
    ) {
        // Its own layer, so the perforations are real holes over a photo poster too.
        Canvas(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val r = size.width * 0.045f
            drawRect(DriveCardText)
            val across = 8; val down = 9
            for (i in 0..across) for (y in listOf(0f, size.height)) drawCircle(Color.Black, r, Offset(i * size.width / across, y), blendMode = BlendMode.Clear)
            for (i in 0..down) for (x in listOf(0f, size.width)) drawCircle(Color.Black, r, Offset(x, i * size.height / down), blendMode = BlendMode.Clear)
            val inset = size.width * 0.085f
            drawRect(DriveCardBackground, Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2))
        }
        StampArt(place, collected = true, size = width * 0.7f, ink = DriveCardText, tilted = false)
    }
}

/**
 * The cut between the route and the figures, as on a ticket stub: a dotted line across the card with a notch at each
 * edge. [inset] is the card's padding, so the notches sit on the card's real edges.
 */
@Composable
internal fun CardTearLine(inset: Dp, modifier: Modifier = Modifier, endNotch: Float = 1f) {
    val d = LocalCarDimens.current
    val behind = MaterialTheme.colorScheme.background
    Canvas(
        modifier.fillMaxWidth().height(d.u(16)).layout { measurable, constraints ->
            val extra = inset.roundToPx() * 2
            val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-inset.roundToPx(), 0) }
        },
    ) {
        val y = size.height / 2
        val dot = 2.2f * density
        drawLine(
            Color.White.copy(alpha = 0.28f), Offset(inset.toPx() * 0.6f, y), Offset(size.width - inset.toPx() * 0.6f, y), dot,
            StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(0.1f, dot * 4f)),
        )
        val notch = inset.toPx() * 0.55f
        drawCircle(behind, notch, Offset(0f, y))
        // The right notch fades when the card widens past it, so no stray dot is left mid-card.
        if (endNotch > 0f) drawCircle(behind.copy(alpha = behind.alpha * endNotch), notch, Offset(size.width, y))
    }
}

/** The distance, its unit at 0.4 of its size, and what the number is right beneath it. */
@Composable
internal fun CardFigure(record: RecordV, size: Int, label: String, labelColor: Color = DriveCardMuted, unitColor: Color = DriveCardMuted) {
    val d = LocalCarDimens.current
    PosterDistance(record, size, unitColor)
    T(label, d.t(16), Modifier.testTag("card-figure-label"), color = labelColor, maxLines = 1)
}

/** place · time · date with the dots fainter than the words, so the three read as three. */
@Composable
internal fun CardFooterText(parts: List<String>, modifier: Modifier = Modifier, color: Color = DriveCardText) {
    val d = LocalCarDimens.current
    val text = remember(parts, color) {
        buildAnnotatedString {
            parts.forEachIndexed { index, part ->
                if (index > 0) withStyle(SpanStyle(color = color.copy(alpha = 0.4f))) { append(" · ") }
                append(part)
            }
        }
    }
    Text(text, modifier, color = color, fontSize = d.t(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
}
