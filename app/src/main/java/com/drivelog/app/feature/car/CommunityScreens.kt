package com.drivelog.app.feature.car

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.hypot
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.drivelog.app.core.recording.haversineKm
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// The community as a mockup. See CommunityCourses.kt: every screen carries the simulation badge and says so.

@Composable
private fun Chip(label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val colors = MaterialTheme.colorScheme
    val spec = tween<Color>(if (LocalMotionEnabled.current) 180 else 0)
    val fill by animateColorAsState(if (active) colors.primary else soft(), spec, label = "chip fill")
    val text by animateColorAsState(if (active) colors.onPrimary else fg(), spec, label = "chip text")
    Row(
        Modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(10))).background(fill)
            .pressClick(onClick = onClick).semantics { selected = active }.padding(horizontal = d.u(16)).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) { T(label, d.t(17), color = text, bold = active, maxLines = 1) }
}

@Composable
private fun CourseFilter(label: String, options: List<String>, selected: String?, tag: String, onSelect: (String?) -> Unit) {
    val d = LocalCarDimens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(label, d.t(16), Modifier.width(d.u(84)), color = muted(), maxLines = 1)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(d.u(8))) {
            Chip("전체", selected == null, "$tag-all") { onSelect(null) }
            options.forEach { option -> Chip(option, selected == option, "$tag-chip") { onSelect(option) } }
        }
    }
}

/** A small outlined label: a course's region, or that it is the driver's own. */
@Composable
private fun Pill(label: String, strong: Boolean = false) {
    val d = LocalCarDimens.current
    val shape = RoundedCornerShape(d.u(8))
    T(
        label, d.t(14), Modifier.clip(shape).then(if (strong) Modifier.background(fg()) else Modifier.border(1.dp, line(), shape))
            .padding(horizontal = d.u(8), vertical = d.u(2)),
        color = if (strong) MaterialTheme.colorScheme.background else fg(), bold = true, maxLines = 1,
    )
}

@Composable
fun CommunityScreen(
    routeMaps: RouteMapCache, courses: List<CommunityCourse>, regionFilter: String?, themeFilter: String?,
    onRegion: (String?) -> Unit, onTheme: (String?) -> Unit, onCourse: (CommunityCourse) -> Unit, onBack: () -> Unit,
) {
    val d = LocalCarDimens.current
    val shown = remember(courses, regionFilter, themeFilter) { filterCourses(courses, regionFilter, themeFilter) }
    val regions = remember(courses) { courses.map { it.region }.distinct() }
    val motion = LocalMotionEnabled.current
    // Thumbnail maps for this visit only: filtering back and forth does not fetch them again.
    val maps = remember { mutableStateMapOf<String, MapFrame>() }
    Frame(onBack, simulated = true) {
        Column(Modifier.fillMaxSize().padding(horizontal = d.u(22))) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Heading("추천 코스", 32, Modifier.testTag("community-title"))
                Spacer(Modifier.width(d.u(14)))
                T("예시 코스예요. 실제 사용자의 글이 아니에요", d.t(16), Modifier.padding(bottom = d.u(6)).testTag("community-note"), color = muted(), maxLines = 1)
            }
            Spacer(Modifier.height(d.u(14)))
            CourseFilter("지역", regions, regionFilter, "region", onRegion)
            Spacer(Modifier.height(d.u(8)))
            CourseFilter("테마", CourseTags, themeFilter, "theme", onTheme)
            Spacer(Modifier.height(d.u(14)))
            if (shown.isEmpty()) T("조건에 맞는 코스가 없어요", d.t(20), Modifier.testTag("community-empty"), color = muted())
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(d.u(10))) {
                // Filtering moves the rows that stay and fades the rest, so the change explains itself.
                itemsIndexed(shown, key = { _, course -> course.id }) { index, course ->
                    CourseRow(routeMaps, maps, course, index, if (motion) Modifier.animateItem() else Modifier) { onCourse(course) }
                }
                // The thumbnails are too small to carry the map's credit, so it sits once under the list.
                if (maps.isNotEmpty()) item(key = "map-credit") {
                    T("지도 $ATTRIBUTION", d.t(13), Modifier.padding(top = d.u(4), bottom = d.u(8)).testTag("map-credit"), color = muted(), maxLines = 1)
                }
            }
        }
    }
}

/** The stamp a course's destination would earn, if it ends at one. Shown on its thumbnail, so each course has its own mark. */
private fun stampAt(point: MapPoint): StampPlace? =
    StampPlaces.map { it to haversineKm(point.latitude, point.longitude, it.latitude, it.longitude) }
        .filter { it.second <= STAMP_RADIUS_KM }.minByOrNull { it.second }?.first

/**
 * The course over a real map of where it runs, in the drive cards' dark style: a white line from a hollow start to a
 * solid end. The map is fetched only for this parked screen and kept in memory ([maps]); the route itself is drawn
 * here and never sent. Until the map arrives, or without a network, a dot grid stands in, plainly not a map.
 */
@Composable
private fun CourseThumbnail(
    routeMaps: RouteMapCache, course: CommunityCourse, maps: MutableMap<String, MapFrame>, modifier: Modifier,
) {
    val d = LocalCarDimens.current
    var box by remember { mutableStateOf(IntSize.Zero) }
    // Keyed by size too: a map rendered for another size (split window, text scale) would not line up with the route.
    val key = "${course.id}@${box.width}x${box.height}"
    val frame = maps[key]
    LaunchedEffect(key) {
        if (frame != null || box == IntSize.Zero || course.route.size < 2) return@LaunchedEffect
        withTimeoutOrNull(15_000L) { routeMaps.liveFrame(course.route + course.destination, box.width, box.height, dark = true) }
            ?.let { maps[key] = it }
    }
    val stamp = remember(course.id) { stampAt(course.destination) }
    Box(modifier.clip(RoundedCornerShape(d.u(12))).background(DriveCardBackground).onSizeChanged { box = it }) {
        Canvas(Modifier.matchParentSize()) {
            val width = 2.4f * d.scale * density
            if (frame == null) {
                val spacing = 14f * d.scale * density
                val reach = hypot(size.width, size.height) / 2f
                var y = spacing / 2
                while (y < size.height) {
                    var x = spacing / 2
                    while (x < size.width) {
                        val fade = 1f - (Offset(x, y) - center).getDistance() / reach
                        if (fade > 0.1f) drawCircle(Color.White.copy(alpha = 0.26f * fade), 1.4f * density, Offset(x, y))
                        x += spacing
                    }
                    y += spacing
                }
                return@Canvas
            }
            val sx = size.width / frame.image.width
            val sy = size.height / frame.image.height
            drawImage(frame.image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            // A soft shade towards the bottom, so the white line and the stamp read on any map.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f))))
            val line = course.route.map { frame.project(it).let { p -> Offset(p.x * sx, p.y * sy) } }
            val path = Path().apply { line.forEachIndexed { i, at -> if (i == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y) } }
            drawPath(path, Color.Black.copy(alpha = 0.4f), style = Stroke(width * 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, DriveCardText, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(DriveCardBackground, width * 2.1f, line.first())
            drawCircle(DriveCardText, width * 2.1f, line.first(), style = Stroke(width * 0.9f))
            drawCircle(DriveCardText, width * 1.9f, line.last())
        }
        if (frame == null) {
            RouteGlyph(course.route, Modifier.matchParentSize().padding(horizontal = d.u(18), vertical = d.u(14)), color = DriveCardText, stroke = d.u(2.4f))
        }
        if (stamp != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(d.u(6)).size(d.u(40)).clip(CircleShape).background(DriveCardBackground.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) { StampArt(stamp, collected = true, size = d.u(34), ink = DriveCardText) }
        }
    }
}

/** One fixed-width column of the course table: a small label over its value. */
@Composable
private fun CourseStat(label: String, value: String, unit: String = "") {
    val d = LocalCarDimens.current
    Column(Modifier.width(d.u(104)), horizontalAlignment = Alignment.End) {
        T(label, d.t(14), color = muted(), maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            T(value, d.t(24), maxLines = 1, display = true, tabular = true)
            if (unit.isNotEmpty()) T(unit, d.t(15), Modifier.padding(start = d.u(3), bottom = d.u(3)), bold = true, maxLines = 1)
        }
    }
}

@Composable
private fun CourseRow(routeMaps: RouteMapCache, maps: MutableMap<String, MapFrame>, course: CommunityCourse, index: Int, modifier: Modifier, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    Row(
        modifier.revealOnce(index).fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(14))).background(soft())
            .pressClick(onClick = onClick).padding(horizontal = d.u(16), vertical = d.u(14)).testTag("course-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CourseThumbnail(routeMaps, course, maps, Modifier.width(d.u(168)).height(d.u(112)))
        Spacer(Modifier.width(d.u(18)))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(d.u(6)), verticalAlignment = Alignment.CenterVertically) {
                Pill(course.region)
                if (course.mine) Pill("내 추천", strong = true)
            }
            Spacer(Modifier.height(d.u(6)))
            T(course.title, d.t(21), bold = true, maxLines = 1)
            Spacer(Modifier.height(d.u(2)))
            T(course.tags.joinToString("  ") { "#$it" }.ifEmpty { "테마 없음" }, d.t(15), color = muted(), maxLines = 1)
        }
        CourseStat("거리", distanceText(course.distanceKm), "km")
        CourseStat("시간", "${course.minutes}", "분")
        CourseStat("추천", if (course.mine) "—" else "${course.recommendations}")
        Spacer(Modifier.width(d.u(12)))
        CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(20), muted())
    }
}

/** The same spec sheet for every course: region and themes on top, the distance, then a fixed set of rows. */
@Composable
private fun ColumnScope.CourseSpec(region: String, tags: List<String>, destinationName: String, distanceKm: Double, minutes: Long) {
    val d = LocalCarDimens.current
    Row(horizontalArrangement = Arrangement.spacedBy(d.u(6))) {
        Pill(region)
        tags.forEach { Pill("#$it") }
    }
    Spacer(Modifier.height(d.u(10)))
    Row(verticalAlignment = Alignment.Bottom) {
        T(distanceText(rememberCountUp(distanceKm)), d.t(64), maxLines = 1, display = true, tabular = true)
        T("km", d.t(28), Modifier.padding(start = d.u(8), bottom = d.u(9)), bold = true)
    }
    T("공개되는 구간의 거리", d.t(15), color = muted())
    Spacer(Modifier.height(d.u(14)))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(d.u(12))).border(1.dp, line(), RoundedCornerShape(d.u(12))).padding(horizontal = d.u(16), vertical = d.u(6))) {
        SpecRow("소요 시간", "약 ${minutes}분 · ${courseDurationLabel(minutes)}")
        SpecRow("테마", tags.joinToString(" · ").ifEmpty { "선택 안 함" })
        SpecRow("도착", destinationName, last = true)
    }
}

@Composable
private fun SpecRow(label: String, value: String, last: Boolean = false) {
    val d = LocalCarDimens.current
    Row(Modifier.fillMaxWidth().padding(vertical = d.u(8)), verticalAlignment = Alignment.CenterVertically) {
        T(label, d.t(16), Modifier.width(d.u(96)), color = muted(), maxLines = 1)
        T(value, d.t(18), Modifier.weight(1f), bold = true, maxLines = 1)
    }
    if (!last) Spacer(Modifier.fillMaxWidth().height(1.dp).background(line()))
}

@Composable
fun CourseDetailScreen(routeMaps: RouteMapCache, course: CommunityCourse, onNavigate: () -> Unit, onRemove: () -> Unit, onBack: () -> Unit) {
    val d = LocalCarDimens.current
    var confirmRemove by remember(course.id) { mutableStateOf(false) }
    Frame(onBack, simulated = true) {
        Panes(0.42f, left = {
            Column(Modifier.padding(start = d.u(22))) {
                Heading(course.title, 32, Modifier.testTag("course-title"))
                Spacer(Modifier.height(d.u(8)))
                CourseSpec(course.region, course.tags, course.destinationName, course.distanceKm, course.minutes)
                Spacer(Modifier.height(d.u(14)))
                T(if (course.mine) "예시 화면이에요. 실제로 올라가지 않았어요" else "예시 코스예요. 경로는 대략적인 선이에요", d.t(16), Modifier.testTag("course-note"), color = muted())
                Spacer(Modifier.height(d.u(18)))
                PrimaryButton("이 목적지로 안내", "course-navigate", Modifier.fillMaxWidth(), onClick = onNavigate)
                Spacer(Modifier.height(d.u(6)))
                // SDK routes go to one destination, so promising this exact line would be untrue.
                T("안내는 목적지까지만 해요. 이 경로 그대로 안내하지는 않아요", d.t(15), color = muted())
                if (course.mine) {
                    Spacer(Modifier.height(d.u(10)))
                    SecondaryButton(if (confirmRemove) "한 번 더 누르면 내려요" else "내 추천 내리기", "course-remove", Modifier.fillMaxWidth()) {
                        if (confirmRemove) onRemove() else confirmRemove = true
                    }
                }
            }
        }, right = {
            LiveRouteMap(routeMaps, course.route, course.destination, course.destinationName, paneFill(300).padding(bottom = d.u(8)))
        })
    }
}

@Composable
fun CourseShareScreen(routeMaps: RouteMapCache, draft: CourseDraft?, onPublish: (List<String>) -> Unit, onBack: () -> Unit) {
    val d = LocalCarDimens.current
    var tags by remember { mutableStateOf(emptyList<String>()) }
    Frame(onBack, simulated = true) {
        if (draft == null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Heading("이 기록은 추천할 수 없어요", 32, Modifier.testTag("course-too-short"))
                Spacer(Modifier.height(d.u(8)))
                T("앞뒤 500m를 빼면 남는 구간이 없거나 목적지 정보가 없어요", d.t(18), color = muted())
                Spacer(Modifier.height(d.u(20)))
                SecondaryButton("돌아가기", "course-share-back", onClick = onBack)
            }
            return@Frame
        }
        Panes(0.46f, left = {
            Column(Modifier.padding(start = d.u(22))) {
                Heading("이렇게 공개돼요", 32, Modifier.testTag("course-share-title"))
                Spacer(Modifier.height(d.u(10)))
                CourseSpec(courseRegion(draft.originArea), tags, draft.destinationName, routeKm(draft.route), draft.minutes)
                Spacer(Modifier.height(d.u(12)))
                T("출발지는 동네까지만 보여요. 경로의 앞뒤 500m는 공개되지 않아요", d.t(17), Modifier.testTag("course-privacy"))
                Spacer(Modifier.height(d.u(14)))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(d.u(8))) {
                    CourseTags.forEach { tag ->
                        Chip(tag, tag in tags, "course-tag") { tags = if (tag in tags) tags - tag else tags + tag }
                    }
                }
                Spacer(Modifier.height(d.u(16)))
                PrimaryButton("코스 추천하기", "course-publish", Modifier.fillMaxWidth()) { onPublish(tags) }
                Spacer(Modifier.height(d.u(6)))
                T("예시 화면이에요. 실제로 올라가지 않고 앱을 끄면 사라져요", d.t(15), Modifier.testTag("course-note"), color = muted())
            }
        }, right = {
            LiveRouteMap(routeMaps, draft.route, draft.destination, draft.destinationName, paneFill(300).padding(bottom = d.u(8)))
        })
    }
}
