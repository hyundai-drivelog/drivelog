package com.drivelog.app.feature.car

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// The community as a mockup. See CommunityCourses.kt: every screen carries the simulation badge and says so.

/** One labelled row of filter chips, scrolling sideways like komoot's and AllTrails' filter bars. */
@Composable
private fun CourseFilter(label: String, options: List<String>, selected: String?, tag: String, onSelect: (String?) -> Unit) {
    val d = LocalCarDimens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(label, d.t(CarTextSize.CAPTION), Modifier.width(d.u(96)), color = muted(), maxLines = 1)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(d.u(CarSpace.S))) {
            ChoiceChip("전체", selected == null, "$tag-all") { onSelect(null) }
            options.forEach { option -> ChoiceChip(option, selected == option, "$tag-chip") { onSelect(option) } }
        }
    }
}

@Composable
fun CommunityScreen(
    routeMaps: RouteMapCache, courses: List<CommunityCourse>, originFilter: String?, destinationFilter: String?,
    onOrigin: (String?) -> Unit, onDestination: (String?) -> Unit, onCourse: (CommunityCourse) -> Unit, onBack: () -> Unit,
) {
    val d = LocalCarDimens.current
    val shown = remember(courses, originFilter, destinationFilter) { filterCourses(courses, originFilter, destinationFilter) }
    val motion = LocalMotionEnabled.current
    Frame(onBack, simulated = true) {
        Column(Modifier.fillMaxSize().padding(horizontal = d.u(22))) {
            Heading("추천 코스", CarTextSize.TITLE.baseSp, Modifier.testTag("community-title"))
            T("예시 코스예요. 실제 사용자의 글이 아니에요", d.t(CarTextSize.CAPTION), Modifier.padding(top = d.u(CarSpace.XS)).testTag("community-note"), color = muted())
            Spacer(Modifier.height(d.u(CarSpace.M)))
            CourseFilter("출발 지역", courses.map { it.originArea }.distinct(), originFilter, "origin", onOrigin)
            Spacer(Modifier.height(d.u(CarSpace.S)))
            CourseFilter("목적지", courses.map { it.destinationName }.distinct(), destinationFilter, "destination", onDestination)
            Spacer(Modifier.height(d.u(CarSpace.M)))
            if (shown.isEmpty()) EmptyState("조건에 맞는 코스가 없어요", Modifier.fillMaxWidth().testTag("community-empty"), body = "출발 지역이나 목적지를 ‘전체’로 바꿔 보세요")
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(d.u(CarSpace.S))) {
                // Filtering moves the rows that stay and fades the rest, so the change explains itself.
                itemsIndexed(shown, key = { _, course -> course.id }) { index, course ->
                    CourseRow(routeMaps, course, index, if (motion) Modifier.animateItem() else Modifier) { onCourse(course) }
                }
                item(key = "map-credit") { T(ATTRIBUTION, d.t(12), Modifier.padding(top = d.u(CarSpace.XS)).testTag("course-map-credit"), color = muted(), maxLines = 2) }
            }
        }
    }
}

/**
 * A course as route-discovery feeds show one (komoot tours, AllTrails, Strava routes): the map tile, the name and
 * where it runs, and the distance as the one large figure on the right.
 */
@Composable
private fun CourseRow(routeMaps: RouteMapCache, course: CommunityCourse, index: Int, modifier: Modifier, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    Row(
        modifier.revealOnce(index).fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(CarRadius.TILE))).background(soft())
            .pressClick(onClick = onClick).padding(d.u(CarSpace.S)).padding(end = d.u(CarSpace.S)).testTag("course-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CourseThumbnail(routeMaps, course.route, Modifier.size(d.u(144), d.u(104)))
        Spacer(Modifier.width(d.u(CarSpace.M)))
        Column(Modifier.weight(1f)) {
            T((if (course.mine) "내 추천 · " else "") + course.title, d.t(CarTextSize.BODY), bold = true, maxLines = 1)
            T("${course.originArea} → ${course.destinationName}", d.t(CarTextSize.CAPTION), Modifier.padding(top = d.u(CarSpace.XS)), maxLines = 1)
            if (course.tags.isNotEmpty()) T(course.tags.joinToString(" · "), d.t(CarTextSize.CAPTION), color = muted(), maxLines = 1)
        }
        Spacer(Modifier.width(d.u(CarSpace.M)))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(distanceText(course.distanceKm), d.t(CarTextSize.LABEL), maxLines = 1, display = true, tabular = true)
                T("km", d.t(CarTextSize.CAPTION), Modifier.padding(start = d.u(CarSpace.XS), bottom = d.u(2)), bold = true)
            }
            T(if (course.mine) "${course.minutes}분" else "${course.minutes}분 · 추천 ${course.recommendations}", d.t(CarTextSize.CAPTION), color = muted(), maxLines = 1)
        }
        Spacer(Modifier.width(d.u(CarSpace.S)))
        CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(24), muted())
    }
}

/** The route, then the distance in the same number-over-label pair every screen and card uses. */
@Composable
private fun CourseFacts(originArea: String, destinationName: String, distanceKm: Double, minutes: Long) {
    val d = LocalCarDimens.current
    Column {
        T("$originArea → $destinationName", d.t(CarTextSize.BODY), bold = true)
        Spacer(Modifier.height(d.u(CarSpace.M)))
        Stat(distanceText(rememberCountUp(distanceKm)), "km", "공개되는 구간의 거리 · ${minutes}분", size = 72)
    }
}

@Composable
fun CourseDetailScreen(routeMaps: RouteMapCache, course: CommunityCourse, onNavigate: () -> Unit, onRemove: () -> Unit, onBack: () -> Unit) {
    val d = LocalCarDimens.current
    var confirmRemove by remember(course.id) { mutableStateOf(false) }
    Frame(onBack, simulated = true) {
        Panes(0.42f, left = {
            Column(Modifier.padding(start = d.u(22))) {
                Heading(course.title, CarTextSize.TITLE.baseSp, Modifier.testTag("course-title"))
                Spacer(Modifier.height(d.u(CarSpace.S)))
                CourseFacts(course.originArea, course.destinationName, course.distanceKm, course.minutes)
                if (course.tags.isNotEmpty()) T(course.tags.joinToString(" · "), d.t(18), Modifier.padding(top = d.u(CarSpace.S)))
                T(
                    if (course.mine) "예시 화면이에요. 실제로 올라가지 않았어요" else "예시 코스예요. 경로는 대략적인 선이에요",
                    d.t(CarTextSize.CAPTION), Modifier.padding(top = d.u(CarSpace.M)).testTag("course-note"), color = muted(),
                )
                Spacer(Modifier.height(d.u(CarSpace.L)))
                PrimaryButton("이 목적지로 안내", "course-navigate", Modifier.fillMaxWidth(), onClick = onNavigate)
                // SDK routes go to one destination, so promising this exact line would be untrue.
                T("안내는 목적지까지만 해요. 이 경로 그대로 안내하지는 않아요", d.t(CarTextSize.CAPTION), Modifier.padding(top = d.u(CarSpace.S)), color = muted())
                if (course.mine) {
                    Spacer(Modifier.height(d.u(CarSpace.M)))
                    SecondaryButton(if (confirmRemove) "한 번 더 누르면 내려요" else "내 추천 내리기", "course-remove", Modifier.fillMaxWidth(), danger = confirmRemove) {
                        if (confirmRemove) onRemove() else confirmRemove = true
                    }
                }
            }
        }, right = {
            LiveRouteMap(routeMaps, course.route, course.destination, course.destinationName, paneFill(300).padding(bottom = d.u(CarSpace.S)))
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
                Column(Modifier.widthIn(max = d.u(640)), horizontalAlignment = Alignment.CenterHorizontally) {
                    Heading("이 기록은 추천할 수 없어요", CarTextSize.TITLE.baseSp, Modifier.testTag("course-too-short"), align = TextAlign.Center)
                    T("앞뒤 500m를 빼면 남는 구간이 없거나 목적지 정보가 없어요", d.t(18), Modifier.padding(top = d.u(CarSpace.S)), color = muted(), align = TextAlign.Center)
                    Spacer(Modifier.height(d.u(CarSpace.L)))
                    SecondaryButton("돌아가기", "course-share-back", size = ButtonSize.MEDIUM, onClick = onBack)
                }
            }
            return@Frame
        }
        Panes(0.46f, left = {
            Column(Modifier.padding(start = d.u(22))) {
                Heading("이렇게 공개돼요", CarTextSize.TITLE.baseSp, Modifier.testTag("course-share-title"))
                Spacer(Modifier.height(d.u(CarSpace.S)))
                CourseFacts(draft.originArea, draft.destinationName, routeKm(draft.route), draft.minutes)
                T("출발지는 동네까지만 보여요. 경로의 앞뒤 500m는 공개되지 않아요", d.t(17), Modifier.padding(top = d.u(CarSpace.M)).testTag("course-privacy"))
                Spacer(Modifier.height(d.u(CarSpace.M)))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(d.u(CarSpace.S))) {
                    CourseTags.forEach { tag ->
                        ChoiceChip(tag, tag in tags, "course-tag") { tags = if (tag in tags) tags - tag else tags + tag }
                    }
                }
                Spacer(Modifier.height(d.u(CarSpace.L)))
                PrimaryButton("코스 추천하기", "course-publish", Modifier.fillMaxWidth()) { onPublish(tags) }
                T("예시 화면이에요. 실제로 올라가지 않고 앱을 끄면 사라져요", d.t(CarTextSize.CAPTION), Modifier.padding(top = d.u(CarSpace.S)).testTag("course-note"), color = muted())
            }
        }, right = {
            LiveRouteMap(routeMaps, draft.route, draft.destination, draft.destinationName, paneFill(300).padding(bottom = d.u(CarSpace.S)))
        })
    }
}
