package com.drivelog.app.feature.car

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** One page of the first-run introduction: what the driver does, and what the app does in return. */
private data class WelcomePage(val title: String, val body: String, val visual: @Composable (Modifier) -> Unit)

/** A made-up route for the last page's card. Shown with an `예시` label: it is no drive anyone made. */
private val ExampleRoute = (0..60).map { i ->
    val t = i / 60.0
    // A coast road's sweep: mostly eastward, bending north and back as it goes.
    MapPoint(35.100 + 0.022 * t + 0.006 * kotlin.math.sin(t * 7.0), 129.020 + 0.075 * t + 0.003 * kotlin.math.cos(t * 5.0))
}

/**
 * Shown once, on the first start, and again from the home screen's `앱 소개`. Parked only like every screen
 * but the recording one; it starts nothing and sends nothing, and skipping it loses nothing.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val d = LocalCarDimens.current
    val motion = LocalMotionEnabled.current
    val pages = listOf(
        WelcomePage(
            "달린 길이\n카드로 남아요",
            "목적지를 고르면 Pleos 내비 안내와 기록이 함께 시작되고, 정차하면 경로를 그린 드라이브 카드가 남아요.",
        ) { modifier -> PhotoVisual(modifier) },
        WelcomePage(
            "목적지 하나만\n고르세요",
            "말하거나 입력하면 내비가 장소를 찾아요. 최근 검색과 저장한 장소에서 바로 고를 수도 있어요.",
        ) { modifier -> GlyphVisual(CarGlyph.SEARCH, modifier) },
        WelcomePage(
            "달리는 동안은\n단순하게",
            "주행 중 화면에는 장소, 분 단위 시간, 거리만 보여요. 길 안내는 기본 내비가 맡아요.",
        ) { modifier -> DrivingVisual(modifier) },
        WelcomePage(
            "정차하면\n카드를 받아요",
            "카드를 누르거나 옆으로 밀면 자세한 기록이 열려요. 경로는 서버에 저장하지 않고 차 안에서 그려요.",
        ) { modifier -> CardVisual(modifier) },
    )
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    // Back steps through the pages as 이전 does; only on the first page does it leave the introduction.
    androidx.activity.compose.BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { if (motion) pager.animateScrollToPage(pager.currentPage - 1) else pager.scrollToPage(pager.currentPage - 1) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8)).testTag("welcome")) {
        TopBar(null, null, { SecondaryButton("건너뛰기", "welcome-skip", size = ButtonSize.SMALL, onClick = onDone) })
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) { index ->
            val page = pages[index]
            val text: @Composable () -> Unit = {
                Column {
                    T(page.title, d.t(48), display = true, maxLines = 3)
                    Spacer(Modifier.height(d.u(18)))
                    T(page.body, d.t(21), color = muted(), maxLines = 4)
                }
            }
            if (d.stacked) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.u(24))) {
                    page.visual(Modifier.fillMaxWidth().height(d.u(300)))
                    text()
                }
            } else {
                Row(Modifier.fillMaxSize().padding(vertical = d.u(10)), horizontalArrangement = Arrangement.spacedBy(d.u(48)), verticalAlignment = Alignment.CenterVertically) {
                    page.visual(Modifier.weight(0.52f).fillMaxHeight())
                    Box(Modifier.weight(0.48f)) { text() }
                }
            }
        }
        Spacer(Modifier.height(d.u(14)))
        if (d.stacked) {
            PageDots(pages.size, pager.currentPage)
            Spacer(Modifier.height(d.u(14)))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!d.stacked) PageDots(pages.size, pager.currentPage)
            Spacer(Modifier.weight(1f))
            if (pager.currentPage > 0) {
                SecondaryButton("이전", "welcome-back", size = ButtonSize.MEDIUM) {
                    scope.launch { if (motion) pager.animateScrollToPage(pager.currentPage - 1) else pager.scrollToPage(pager.currentPage - 1) }
                }
                Spacer(Modifier.width(d.u(12)))
            }
            PrimaryButton(if (last) "시작하기" else "다음", "welcome-next", if (d.stacked) Modifier.weight(1f) else Modifier.width(d.u(320)), chevron = !last, size = ButtonSize.MEDIUM) {
                if (last) onDone()
                else scope.launch { if (motion) pager.animateScrollToPage(pager.currentPage + 1) else pager.scrollToPage(pager.currentPage + 1) }
            }
        }
        Spacer(Modifier.height(d.u(10)))
    }
}

/** Pleos's indicator: the current page at full ink, the rest at 32%. The current one is a pill. */
@Composable
private fun PageDots(count: Int, current: Int) {
    val d = LocalCarDimens.current
    val motion = LocalMotionEnabled.current
    Row(horizontalArrangement = Arrangement.spacedBy(d.u(8)), modifier = Modifier.testTag("welcome-dots")) {
        repeat(count) { index ->
            val active = index == current
            val width by animateDpAsState(if (active) d.u(28) else d.u(10), tween(if (motion) MOTION_STATE_MS else 0), label = "dot width")
            val color by animateColorAsState(fg().copy(alpha = if (active) 1f else 0.32f), tween(if (motion) MOTION_STATE_MS else 0), label = "dot color")
            Box(Modifier.width(width).height(d.u(10)).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun PhotoVisual(modifier: Modifier) {
    val d = LocalCarDimens.current
    Box(modifier.clip(RoundedCornerShape(d.u(14))).background(DriveCardBackground)) {
        // The mood photo that names nowhere, as on the home screen before a first drive.
        PlacePhoto("", Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        Row(Modifier.align(Alignment.BottomStart).padding(d.u(28)), verticalAlignment = Alignment.CenterVertically) {
            BrandGlyph(d.u(52), DriveCardText)
            Spacer(Modifier.width(d.u(12)))
            BrandWordmark(d.t(40), DriveCardText)
        }
    }
}

@Composable
private fun GlyphVisual(glyph: CarGlyph, modifier: Modifier) {
    val d = LocalCarDimens.current
    Box(modifier.clip(RoundedCornerShape(d.u(14))).background(soft()), contentAlignment = Alignment.Center) {
        Box(Modifier.size(d.u(180)).clip(CircleShape).background(DriveCardBackground), contentAlignment = Alignment.Center) {
            CarIcon(glyph, d.u(88), DriveCardText)
        }
    }
}

/** The recording screen's three figures, set as it shows them while driving. */
@Composable
private fun DrivingVisual(modifier: Modifier) {
    val d = LocalCarDimens.current
    Column(
        modifier.clip(RoundedCornerShape(d.u(14))).background(DriveCardBackground).padding(d.u(36)),
        verticalArrangement = Arrangement.Center,
    ) {
        T("예시", d.t(15), color = DriveCardMuted, bold = true)
        Spacer(Modifier.height(d.u(18)))
        listOf("송정 해수욕장" to "장소", "24분" to "기록 시간", "12.4 km" to "거리").forEach { (value, label) ->
            T(value, d.t(52), color = DriveCardText, display = true, maxLines = 1)
            T(label, d.t(17), Modifier.padding(bottom = d.u(16)), color = DriveCardMuted)
        }
    }
}

/** A card in the house style around a made-up route, labelled as an example. */
@Composable
private fun CardVisual(modifier: Modifier) {
    val d = LocalCarDimens.current
    // The order every card keeps: title, route, distance, then place · time · date and the wordmark.
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(DriveCardBackground).border(1.dp, line(), RoundedCornerShape(24.dp)).padding(d.u(24))) {
        T("예시 카드", d.t(16), color = DriveCardMuted, bold = true)
        T("송정에 남긴 하루", d.t(28), color = DriveCardText, display = true, maxLines = 1)
        Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = d.u(18))) {
            RouteGlyph(ExampleRoute, Modifier.matchParentSize(), DriveCardText, d.u(4), trace = true)
        }
        CardTearLine(d.u(24), Modifier.padding(bottom = d.u(8)))
        Row(verticalAlignment = Alignment.Bottom) {
            T("12.4", d.t(56), color = DriveCardText, display = true)
            T("km", d.t(22), Modifier.padding(start = d.u(6), bottom = d.u(8)), color = DriveCardMuted, display = true)
        }
        T("예시 거리", d.t(16), color = DriveCardMuted)
        Spacer(Modifier.height(d.u(6)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardFooterText(listOf("송정", "24분", "예시"), Modifier.weight(1f))
            BrandWordmark(d.t(16), DriveCardText)
        }
    }
}
