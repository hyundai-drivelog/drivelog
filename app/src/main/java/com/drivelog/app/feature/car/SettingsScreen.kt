package com.drivelog.app.feature.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(soundEnabled: Boolean, onToggleSound: () -> Unit, onPrivacy: () -> Unit, onBack: () -> Unit) {
    val d = LocalCarDimens.current
    Frame(onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings-screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = d.u(840)).fillMaxWidth().padding(vertical = d.u(CarSpace.L)),
                verticalArrangement = Arrangement.spacedBy(d.u(CarSpace.L)),
            ) {
                Heading("설정", CarTextSize.HEADING.baseSp)
                HorizontalDivider(color = line())
                Row(
                    Modifier.fillMaxWidth().heightIn(min = maxOf(d.u(100), 64.dp))
                        .pressClick(onClick = onPrivacy).padding(vertical = d.u(CarSpace.M)).testTag("open-privacy"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.u(CarSpace.M)),
                ) {
                    CarIcon(CarGlyph.DOCUMENT, d.u(36), fg())
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d.u(CarSpace.S))) {
                        T("개인정보와 데이터 사용", d.t(CarTextSize.LABEL), bold = true)
                        T("기록의 보관·전송·삭제 안내", d.t(CarTextSize.BODY), color = muted())
                    }
                    CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(28), muted())
                }
                HorizontalDivider(color = line())
                Row(
                    Modifier.fillMaxWidth().heightIn(min = maxOf(d.u(100), 64.dp))
                        .toggleable(value = soundEnabled, role = Role.Switch, onValueChange = { onToggleSound() })
                        .padding(vertical = d.u(CarSpace.M)).testTag("toggle-sound-effects"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.u(CarSpace.M)),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d.u(CarSpace.S))) {
                        T("효과음", d.t(CarTextSize.LABEL), bold = true)
                        T(
                            if (soundEnabled) "켜짐 · 기록 시작과 저장 결과를 소리로 알려요" else "꺼짐 · 기존 음성 안내는 유지돼요",
                            d.t(CarTextSize.BODY), color = muted(),
                        )
                    }
                    Switch(checked = soundEnabled, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
                }
                HorizontalDivider(color = line())
            }
        }
    }
}
