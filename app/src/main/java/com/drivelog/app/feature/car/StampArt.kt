package com.drivelog.app.feature.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * A rubber stamp of the place: a double ring around a line drawing of what the place is known for. One ink, as the
 * Pleos tokens set colour, and drawn here rather than taken from any logo or photo. A place not yet visited shows its
 * picture faintly inside a dashed ring, so the driver can see what is there to collect. It never moves.
 */
@Composable
fun StampArt(
    place: StampPlace, collected: Boolean, size: Dp, modifier: Modifier = Modifier,
    /** The drive card's stamp is always white on its dark paper, whatever the theme. */
    ink: Color? = null,
    /** False where the stamp sits on a postage stamp that is tilted already. */
    tilted: Boolean = true,
) {
    val ink = ink ?: if (collected) fg() else line()
    Canvas(
        modifier.size(size).rotate(if (collected && tilted) place.tilt else 0f).testTag("stamp-art-${place.id}")
            .semantics { contentDescription = place.name + if (collected) " 스탬프" else " 스탬프, 아직 방문 전" },
    ) {
        val u = this.size.width / 100f
        val center = Offset(50f * u, 50f * u)
        val ring = Stroke(4f * u, pathEffect = if (collected) null else PathEffect.dashPathEffect(floatArrayOf(6f * u, 5f * u)))
        drawCircle(ink, 46f * u, center, style = ring)
        drawCircle(ink, 40f * u, center, style = Stroke(1.6f * u))
        val inside = Path().apply { addOval(Rect(center, 37f * u)) }
        clipPath(inside) { StampPen(this, u, ink).draw(place.art) }
    }
}

/** Draws on a 100-unit square; the motif keeps within the inner ring at 50,50. */
private class StampPen(val scope: DrawScope, val u: Float, val ink: Color) {
    private val stroke = Stroke(3.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val thin = Stroke(2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

    private fun p(x: Float, y: Float) = Offset(x * u, y * u)
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 3.2f) =
        scope.drawLine(ink, p(x1, y1), p(x2, y2), width * u, StrokeCap.Round)
    private fun path(thinLine: Boolean = false, fill: Boolean = false, build: Path.() -> Unit) {
        val shape = Path().apply(build)
        if (fill) scope.drawPath(shape, ink) else scope.drawPath(shape, ink, style = if (thinLine) thin else stroke)
    }
    private fun Path.m(x: Float, y: Float) = moveTo(x * u, y * u)
    private fun Path.l(x: Float, y: Float) = lineTo(x * u, y * u)
    private fun Path.q(cx: Float, cy: Float, x: Float, y: Float) = quadraticTo(cx * u, cy * u, x * u, y * u)

    /** A row of small swells, the sea in every stamp that has one. */
    private fun waves(y: Float, from: Float = 14f, to: Float = 86f, step: Float = 9f) = path(thinLine = true) {
        m(from, y)
        var x = from
        while (x < to) { q(x + step / 2, y - 3.5f, x + step, y); x += step }
    }

    fun draw(id: String) {
        when (id) {
            // 광안대교: two towers, the hanging cables and the deck over the water.
            "gwangalli" -> {
                line(14f, 60f, 86f, 60f)
                line(37f, 30f, 37f, 60f)
                line(63f, 30f, 63f, 60f)
                path { m(14f, 46f); q(28f, 44f, 37f, 30f); q(50f, 54f, 63f, 30f); q(72f, 44f, 86f, 46f) }
                listOf(44f, 50f, 56f).forEach { x -> line(x, 60f, x, 40f + (x - 50f) * (x - 50f) / 18f, 1.6f) }
                waves(70f); waves(79f, step = 11f)
            }
            // 해운대: a parasol on the sand under the sun, with the sea beyond.
            "haeundae" -> {
                scope.drawCircle(ink, 7f * u, p(68f, 30f), style = stroke)
                path { m(22f, 46f); q(38f, 24f, 54f, 46f); close() }
                line(38f, 36f, 38f, 64f)
                path(thinLine = true) { m(30f, 46f); q(34f, 38f, 38f, 46f); q(42f, 38f, 46f, 46f) }
                path { m(14f, 64f); q(50f, 58f, 86f, 64f) }
                waves(74f); waves(82f, step = 11f)
            }
            // 송정: a surfboard standing up beside a wave curling over.
            "songjeong" -> {
                path { m(30f, 72f); q(22f, 46f, 34f, 22f); q(44f, 46f, 30f, 72f) }
                line(32f, 30f, 31f, 64f, 1.6f)
                path { m(46f, 70f); q(52f, 34f, 76f, 34f); q(88f, 36f, 82f, 48f); q(76f, 54f, 70f, 46f) }
                path(thinLine = true) { m(54f, 70f); q(60f, 50f, 72f, 46f) }
                waves(76f)
            }
            // 해동용궁사: a temple roof with upturned eaves on the rocks above the sea.
            "yonggungsa" -> {
                path { m(24f, 42f); q(34f, 40f, 50f, 26f); q(66f, 40f, 76f, 42f) }
                line(24f, 42f, 21f, 38f, 3f); line(76f, 42f, 79f, 38f, 3f)
                line(32f, 46f, 68f, 46f)
                line(36f, 46f, 36f, 58f); line(64f, 46f, 64f, 58f)
                path(thinLine = true) { m(45f, 58f); l(45f, 50f); l(55f, 50f); l(55f, 58f) }
                path { m(16f, 70f); l(26f, 60f); l(40f, 58f); l(52f, 62f); l(66f, 58f); l(78f, 62f); l(86f, 70f) }
                waves(78f)
            }
            // 오륙도: the glass skywalk reaching out, and the islands in a row out to sea.
            "oryukdo" -> {
                // The cliff, and the horseshoe walkway reaching out from its top.
                path { m(18f, 64f); l(24f, 30f); l(30f, 30f) }
                path { m(30f, 30f); l(40f, 30f); q(48f, 30f, 48f, 37f); q(48f, 44f, 40f, 44f); l(32f, 44f) }
                line(36f, 44f, 36f, 52f, 2f); line(44f, 42f, 44f, 50f, 2f)
                path { m(46f, 64f); q(52f, 46f, 58f, 64f) }
                path { m(58f, 64f); q(63f, 52f, 68f, 64f) }
                path { m(68f, 64f); q(72f, 56f, 76f, 64f) }
                path { m(76f, 64f); q(79f, 59f, 82f, 64f) }
                line(14f, 64f, 86f, 64f, 2f)
                waves(74f); waves(82f, step = 11f)
            }
            // 흰여울: small houses along the cliff and a stairway down to the sea.
            "huinnyeoul" -> {
                path { m(18f, 40f); l(27f, 31f); l(36f, 40f); l(36f, 50f); l(18f, 50f); close() }
                path { m(40f, 36f); l(49f, 27f); l(58f, 36f); l(58f, 46f); l(40f, 46f); close() }
                scope.drawRect(ink, p(25f, 42f), androidx.compose.ui.geometry.Size(4f * u, 4f * u))
                scope.drawRect(ink, p(47f, 38f), androidx.compose.ui.geometry.Size(4f * u, 4f * u))
                path { m(60f, 46f); l(60f, 52f); l(66f, 52f); l(66f, 58f); l(72f, 58f); l(72f, 64f); l(78f, 64f); l(78f, 70f) }
                path { m(14f, 54f); l(56f, 54f); l(62f, 70f) }
                waves(76f); waves(84f, step = 11f)
            }
            // 태종대: a lighthouse on the cliff edge, its light reaching out.
            "taejongdae" -> {
                path { m(44f, 64f); l(47f, 34f); l(57f, 34f); l(60f, 64f) }
                path { m(45f, 34f); l(59f, 34f); l(56f, 26f); l(48f, 26f); close() }
                line(52f, 26f, 52f, 21f, 2.4f)
                line(46f, 46f, 58f, 46f, 2f)
                line(62f, 28f, 80f, 22f, 2f); line(62f, 32f, 82f, 34f, 2f)
                path { m(14f, 64f); l(36f, 64f); l(66f, 64f); l(72f, 70f); l(76f, 80f) }
                waves(78f, from = 14f, to = 66f)
            }
            // 다대포: the sun going down into the sea, its light lying on the water, birds heading home.
            "dadaepo" -> {
                path { m(32f, 56f); q(32f, 36f, 50f, 36f); q(68f, 36f, 68f, 56f) }
                line(14f, 56f, 86f, 56f)
                line(38f, 63f, 62f, 63f, 2f); line(42f, 69f, 58f, 69f, 2f); line(46f, 75f, 54f, 75f, 2f)
                path(thinLine = true) { m(22f, 30f); q(25f, 26f, 28f, 30f); q(31f, 26f, 34f, 30f) }
                path(thinLine = true) { m(68f, 24f); q(70.5f, 21f, 73f, 24f); q(75.5f, 21f, 78f, 24f) }
                waves(82f, step = 11f)
            }
            "palace", "temple", "fortress" -> {
                path { m(19f, 43f); q(34f, 42f, 50f, 27f); q(66f, 42f, 81f, 43f); l(76f, 48f); l(24f, 48f); close() }
                line(28f, 49f, 28f, 69f); line(72f, 49f, 72f, 69f)
                line(20f, 70f, 80f, 70f); line(16f, 76f, 84f, 76f, 2f)
                if (id == "fortress") {
                    path(thinLine = true) { m(16f, 61f); l(16f, 54f); l(23f, 54f); m(77f, 54f); l(84f, 54f); l(84f, 61f) }
                } else {
                    line(42f, 52f, 42f, 68f, 2f); line(58f, 52f, 58f, 68f, 2f)
                }
            }
            "tower", "science", "lighthouse" -> {
                line(50f, 17f, 50f, 29f, 2f)
                path { m(43f, 41f); l(39f, 29f); l(61f, 29f); l(57f, 41f); close() }
                path { m(46f, 42f); l(42f, 73f); l(58f, 73f); l(54f, 42f) }
                line(31f, 75f, 69f, 75f)
                if (id == "science") {
                    scope.drawOval(ink, p(24f, 38f), androidx.compose.ui.geometry.Size(52f * u, 14f * u), style = thin)
                } else if (id == "lighthouse") {
                    line(65f, 28f, 80f, 22f, 2f); line(65f, 33f, 83f, 36f, 2f); waves(82f)
                } else {
                    line(18f, 75f, 18f, 59f, 2f); line(27f, 75f, 27f, 50f, 2f); line(76f, 75f, 76f, 57f, 2f)
                }
            }
            "forest", "bamboo", "tea", "garden" -> {
                if (id == "bamboo") {
                    listOf(32f, 50f, 68f).forEach { x ->
                        line(x, 24f, x, 78f)
                        listOf(38f, 54f, 68f).forEach { y -> line(x - 3f, y, x + 3f, y, 2f); line(x, y - 4f, x + 10f, y - 12f, 2f) }
                    }
                } else if (id == "tea") {
                    repeat(4) { i -> path { m(17f, 49f + i * 8f); q(40f, 29f + i * 7f, 83f, 49f + i * 8f) } }
                    scope.drawCircle(ink, 6f * u, p(68f, 26f), style = thin)
                } else if (id == "garden") {
                    path { m(22f, 69f); l(22f, 45f); q(50f, 12f, 78f, 45f); l(78f, 69f); close() }
                    line(50f, 27f, 50f, 69f, 2f); line(22f, 47f, 78f, 47f, 2f)
                    path(thinLine = true) { m(34f, 69f); l(34f, 45f); q(50f, 24f, 66f, 45f); l(66f, 69f) }
                    line(17f, 75f, 83f, 75f)
                } else {
                    listOf(30f, 50f, 70f).forEach { x ->
                        path { m(x, 25f); l(x - 13f, 53f); l(x - 8f, 53f); l(x - 16f, 65f); l(x + 16f, 65f); l(x + 8f, 53f); l(x + 13f, 53f); close() }
                        line(x, 66f, x, 77f)
                    }
                }
            }
            "lake", "river", "beach", "coast", "rocks", "mountain", "waterfall" -> {
                when (id) {
                    "waterfall" -> {
                        path { m(18f, 32f); l(40f, 32f); l(40f, 61f); m(60f, 61f); l(60f, 32f); l(82f, 32f) }
                        line(47f, 32f, 47f, 65f, 2f); line(54f, 37f, 54f, 69f, 2f)
                    }
                    "rocks", "mountain", "coast" -> {
                        path { m(15f, 62f); l(32f, 36f); l(44f, 58f); l(58f, 27f); l(84f, 62f); close() }
                        path(thinLine = true) { m(51f, 40f); l(58f, 45f); l(64f, 38f) }
                    }
                    "river" -> {
                        path { m(18f, 36f); q(48f, 37f, 50f, 58f); m(82f, 36f); q(55f, 40f, 50f, 58f); l(50f, 77f) }
                    }
                    else -> {
                        scope.drawCircle(ink, 9f * u, p(66f, 32f), style = stroke)
                        path { m(16f, 57f); q(42f, 42f, 82f, 57f) }
                    }
                }
                waves(73f); waves(82f, step = 11f)
            }
            "village", "city", "culture", "gate", "bridge" -> {
                if (id == "bridge" || id == "gate") {
                    path { m(20f, 70f); l(20f, 32f); l(31f, 32f); l(31f, 48f); l(69f, 48f); l(69f, 32f); l(80f, 32f); l(80f, 70f) }
                    line(16f, 57f, 84f, 57f); waves(78f)
                } else {
                    listOf(27f, 50f, 73f).forEachIndexed { i, x ->
                        val top = if (id == "city") 24f + i * 8f else 44f
                        path { m(x - 10f, 70f); l(x - 10f, top); l(x + 10f, top); l(x + 10f, 70f); close() }
                        if (id == "village") path { m(x - 13f, top); l(x, top - 12f); l(x + 13f, top) }
                        line(x, top + 8f, x, top + 14f, 2f)
                    }
                    line(15f, 76f, 85f, 76f)
                    if (id == "culture") path { m(16f, 36f); l(50f, 22f); l(84f, 36f); close() }
                }
            }
            "wheel" -> {
                scope.drawCircle(ink, 24f * u, p(50f, 43f), style = stroke)
                repeat(8) { i ->
                    val angle = i * Math.PI / 4
                    val x = 50f + kotlin.math.cos(angle).toFloat() * 24f
                    val y = 43f + kotlin.math.sin(angle).toFloat() * 24f
                    line(50f, 43f, x, y, 1.6f)
                    scope.drawCircle(ink, 4f * u, p(x, y), style = thin)
                }
                line(50f, 43f, 36f, 79f); line(50f, 43f, 64f, 79f); line(28f, 80f, 72f, 80f)
            }
            "spring", "lotus", "bear", "pagoda", "observatory" -> {
                when (id) {
                    "bear" -> {
                        scope.drawCircle(ink, 7f * u, p(32f, 31f), style = stroke); scope.drawCircle(ink, 7f * u, p(68f, 31f), style = stroke)
                        scope.drawOval(ink, p(25f, 28f), androidx.compose.ui.geometry.Size(50f * u, 45f * u), style = stroke)
                        scope.drawCircle(ink, 2f * u, p(40f, 47f)); scope.drawCircle(ink, 2f * u, p(60f, 47f))
                        scope.drawCircle(ink, 4f * u, p(50f, 58f), style = thin)
                    }
                    "spring" -> {
                        path { m(20f, 59f); q(50f, 86f, 80f, 59f); close() }
                        listOf(35f, 50f, 65f).forEach { x -> path { m(x, 48f); q(x - 9f, 39f, x, 27f) } }
                    }
                    "lotus" -> {
                        path { m(50f, 63f); q(29f, 42f, 50f, 25f); q(71f, 42f, 50f, 63f) }
                        path { m(50f, 63f); q(21f, 66f, 22f, 40f); q(43f, 40f, 50f, 63f); q(79f, 66f, 78f, 40f); q(57f, 40f, 50f, 63f) }
                        waves(75f)
                    }
                    "pagoda" -> {
                        repeat(3) { i -> val y = 34f + i * 15f; val w = 13f + i * 5f
                            path { m(50f - w, y); l(50f, y - 9f); l(50f + w, y); close() }
                            line(50f - w + 5f, y, 50f - w + 5f, y + 7f, 2f); line(50f + w - 5f, y, 50f + w - 5f, y + 7f, 2f)
                        }
                        line(50f, 19f, 50f, 25f); line(20f, 76f, 80f, 76f)
                    }
                    else -> {
                        path { m(32f, 72f); q(43f, 46f, 40f, 29f); l(60f, 29f); q(57f, 46f, 68f, 72f); close() }
                        line(36f, 52f, 64f, 52f, 2f); line(34f, 61f, 66f, 61f, 2f); line(39f, 40f, 61f, 40f, 2f)
                        scope.drawRect(ink, p(47f, 43f), androidx.compose.ui.geometry.Size(6f * u, 6f * u))
                        line(24f, 77f, 76f, 77f)
                    }
                }
            }
            // A place added without a drawing still gets a stamp: its pin.
            else -> {
                path { m(50f, 74f); q(30f, 50f, 34f, 38f); q(40f, 24f, 50f, 24f); q(60f, 24f, 66f, 38f); q(70f, 50f, 50f, 74f) }
                scope.drawCircle(ink, 6f * u, p(50f, 40f), style = stroke)
            }
        }
    }
}
