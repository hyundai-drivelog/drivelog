package com.drivelog.app.feature.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.drivelog.app.BuildConfig

/** Also bundled into every build: the explanation remains available without a server or browser. */
internal val privacySections = listOf(
    "차량에 저장하는 정보" to "목적지 이름·좌표, 주행 위치와 시각, 거리·시간, 선택한 휴게와 차량 에너지 정보를 기록과 카드에 사용해요. 직접 만든 여행 이름과 포함 주행·대표 장소는 이 차량에 보관해요. 여행 묶음 해제는 원본 주행을 지우지 않아요. 기록은 삭제할 때까지 차량의 앱 저장 공간에 보관해요. 위치 권한을 거부하면 기록을 시작하지 않아요. 에너지 권한을 거부해도 기록할 수 있어요.",
    "지도와 음성" to "정차 중 OpenFreeMap에서 지도 조각을 받아요. 요청한 지도 지역과 네트워크 주소가 지도 제공자에게 전달될 수 있어요. 경로 좌표 배열은 지도 제공자에게 보내지 않고 차량에서 그려요. 음성 입력은 누를 때만 Pleos 기기 내 인식을 사용하고 녹음 파일은 보관하지 않아요. 목적지 검색과 길 안내는 기본 내비가 처리해요.",
    "선택한 경우에만 서버 연결" to "휴대폰 연결에서 전송에 동의하면 목적지·경로 좌표·시각·거리·시간·휴게·에너지 정보와 연결 인증 정보가 DRIVELOG 서버로 전달돼요. 휴대폰 기록·카드·스탬프 조회에 사용하고 서버 기록은 삭제 요청 때까지 보관해요. 광고나 운전 데이터 판매에 사용하지 않아요. 연결에 동의하지 않아도 차량 기록은 사용할 수 있어요.",
    "연결 전 기록과 다른 운전자" to "연결 전 차량 기록은 기본적으로 전송하지 않아요. 본인 기록인지 확인한 뒤 별도 선택한 경우에만 포함해요. 기존 차량 기록은 연결을 해제하면 다시 볼 수 있어요. 연결별 기록과 전송 대기열을 분리하며, 연결 해제는 전송을 중지하고 해당 연결 기록을 현재 목록에서 숨겨요. 보관된 연결 기록과 서버 사본의 삭제는 별도예요.",
    "삭제·거부와 문의" to "내 기록에서 기록을 열어 기록 삭제를 두 번 누르면 차량에서 지워요. 연결 중인 기록의 서버 삭제는 네트워크 연결 뒤 반영돼요. 연결을 거부해도 차량 기록·카드·스탬프를 이용할 수 있어요. 연결 해제는 서버 기록 삭제가 아니에요. 앱을 삭제하면 차량 앱 데이터가 지워지지만 서버 사본은 남을 수 있어요. 서버 사본 삭제·열람은 앱을 제공한 운영자에게 요청하세요.",
    "지도·그림 출처" to "지도: OpenFreeMap (openfreemap.org), © OpenMapTiles (openmaptiles.org), © OpenStreetMap contributors (openstreetmap.org/copyright). 스탬프와 예시 경로는 앱의 도형 코드로 그려요. 스탬프는 마지막 관측 위치 1km 안의 명소 기록이며 실제 입장·체류를 인증하지 않아요.",
)

@Composable
fun PrivacyScreen(onBack: () -> Unit, onAgree: (() -> Unit)? = null) {
    val d = LocalCarDimens.current
    Frame(onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("privacy-policy"), verticalArrangement = Arrangement.spacedBy(d.u(20))) {
            Heading("개인정보와 데이터 사용", CarTextSize.HEADING.baseSp)
            T("적용일 2026.11.10 · 차량 기본 모드와 선택적 연결 모드", d.t(CarTextSize.CAPTION), color = muted())
            PrivacyContact()
            privacySections.forEach { (title, body) ->
                Column {
                    T(title, d.t(CarTextSize.TITLE), bold = true)
                    Spacer(Modifier.height(d.u(8)))
                    T(body, d.t(CarTextSize.BODY), color = muted(), maxLines = 20)
                }
            }
            if (onAgree != null) PrimaryButton("차량 기록과 지도 사용에 동의", "agree-data-use", Modifier.fillMaxWidth(), onClick = onAgree)
            SecondaryButton(if (onAgree == null) "돌아가기" else "동의하지 않고 돌아가기", "close-privacy", Modifier.fillMaxWidth(), onClick = onBack)
        }
    }
}

@Composable
fun LinkConsentScreen(localCount: Int, includeHistory: Boolean, onIncludeHistory: () -> Unit, onAgree: () -> Unit, onBack: () -> Unit) {
    val d = LocalCarDimens.current
    Frame(onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.u(20))) {
            Heading("휴대폰 연결 전 확인해 주세요", CarTextSize.HEADING.baseSp)
            PrivacyContact()
            privacySections.drop(2).take(3).forEach { (title, body) ->
                T(title, d.t(CarTextSize.TITLE), bold = true)
                T(body, d.t(CarTextSize.BODY), color = muted(), maxLines = 20)
            }
            if (localCount > 0) SecondaryButton(
                "${if (includeHistory) "선택됨 · " else "선택 안 함 · "}차량의 기존 기록 ${localCount}건도 전송",
                "include-local-history", Modifier.fillMaxWidth(), onClick = onIncludeHistory,
            )
            PrimaryButton("위 내용을 확인하고 서버 전송에 동의", "agree-server-sync", Modifier.fillMaxWidth(), onClick = onAgree)
            SecondaryButton("동의하지 않고 돌아가기", "decline-server-sync", Modifier.fillMaxWidth(), onClick = onBack)
        }
    }
}

@Composable
private fun PrivacyContact() {
    val d = LocalCarDimens.current
    if (BuildConfig.PRIVACY_CONTACT.isNotBlank()) T("운영자·문의: ${BuildConfig.PRIVACY_CONTACT}", d.t(CarTextSize.BODY), maxLines = 4)
    if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) T("공개 처리방침: ${BuildConfig.PRIVACY_POLICY_URL}", d.t(CarTextSize.CAPTION), maxLines = 4)
}
