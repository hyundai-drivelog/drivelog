package com.drivelog.app.feature.car

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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

@Composable
fun CommunityScreen(
    courses: List<CommunityCourse>, originFilter: String?, destinationFilter: String?,
    onOrigin: (String?) -> Unit, onDestination: (String?) -> Unit, onCourse: (CommunityCourse) -> Unit, onBack: () -> Unit,
) {
    val d = LocalCarDimens.current
    val shown = remember(courses, originFilter, destinationFilter) { filterCourses(courses, originFilter, destinationFilter) }
    val motion = LocalMotionEnabled.current
    Frame(onBack, simulated = true) {
        Column(Modifier.fillMaxSize().padding(horizontal = d.u(22))) {
            Heading("추천 코스", 32, Modifier.testTag("community-title"))
            Spacer(Modifier.height(d.u(4)))
            T("예시 코스예요. 실제 사용자의 글이 아니에요", d.t(16), Modifier.testTag("community-note"), color = muted())
            Spacer(Modifier.height(d.u(10)))
            CourseFilter("출발 지역", courses.map { it.originArea }.distinct(), originFilter, "origin", onOrigin)
            Spacer(Modifier.height(d.u(8)))
            CourseFilter("목적지", courses.map { it.destinationName }.distinct(), destinationFilter, "destination", onDestination)
            Spacer(Modifier.height(d.u(12)))
            if (shown.isEmpty()) T("조건에 맞는 코스가 없어요", d.t(20), Modifier.testTag("community-empty"), color = muted())
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(d.u(8))) {
                // Filtering moves the rows that stay and fades the rest, so the change explains itself.
                itemsIndexed(shown, key = { _, course -> course.id }) { index, course ->
                    CourseRow(course, index, if (motion) Modifier.animateItem() else Modifier) { onCourse(course) }
                }
            }
        }
    }
}

@Composable
private fun CourseRow(course: CommunityCourse, index: Int, modifier: Modifier, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    Row(
        modifier.revealOnce(index).fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(12))).background(soft())
            .pressClick(onClick = onClick).padding(horizontal = d.u(14), vertical = d.u(10)).testTag("course-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteGlyph(course.route, Modifier.size(d.u(72)))
        Spacer(Modifier.width(d.u(14)))
        Column(Modifier.weight(1f)) {
            T((if (course.mine) "내 추천 · " else "") + course.title, d.t(21), bold = true, maxLines = 1)
            Spacer(Modifier.height(d.u(2)))
            T("${course.originArea} → ${course.destinationName}", d.t(17), maxLines = 1)
            if (course.tags.isNotEmpty()) T(course.tags.joinToString(" · "), d.t(15), color = muted(), maxLines = 1)
        }
        Spacer(Modifier.width(d.u(14)))
        Column(horizontalAlignment = Alignment.End) {
            T("${distanceText(course.distanceKm)} km", d.t(24), maxLines = 1, display = true)
            T(if (course.mine) "${course.minutes}분" else "${course.minutes}분 · 추천 ${course.recommendations}", d.t(14), color = muted(), maxLines = 1)
        }
        Spacer(Modifier.width(d.u(8)))
        CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(20), fg())
    }
}

@Composable
private fun ColumnScope.CourseFacts(originArea: String, destinationName: String, distanceKm: Double, minutes: Long) {
    val d = LocalCarDimens.current
    T("$originArea → $destinationName", d.t(20), bold = true)
    Spacer(Modifier.height(d.u(14)))
    Row(verticalAlignment = Alignment.Bottom) {
        T(distanceText(rememberCountUp(distanceKm)), d.t(72), maxLines = 1, display = true)
        T("km", d.t(32), Modifier.padding(start = d.u(8), bottom = d.u(10)), bold = true)
    }
    T("공개되는 구간의 거리 · ${minutes}분", d.t(17), color = muted())
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
                CourseFacts(course.originArea, course.destinationName, course.distanceKm, course.minutes)
                if (course.tags.isNotEmpty()) {
                    Spacer(Modifier.height(d.u(10)))
                    T(course.tags.joinToString(" · "), d.t(18))
                }
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
                CourseFacts(draft.originArea, draft.destinationName, routeKm(draft.route), draft.minutes)
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
