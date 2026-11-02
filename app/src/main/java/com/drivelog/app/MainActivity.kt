package com.drivelog.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.drivelog.app.core.vehicle.AutomotiveUxMonitor
import com.drivelog.app.data.sdk.NavigationAction
import com.drivelog.app.feature.card.DriveCardScreen
import com.drivelog.app.feature.diagnostics.PleosDiagnosticsScreen
import com.drivelog.app.feature.drive.DriveScreen
import com.drivelog.app.feature.drive.DriveViewModel
import com.drivelog.app.ui.theme.DrivelogTheme

class MainActivity : ComponentActivity() {
    private var resumed by mutableStateOf(false)
    private var parked by mutableStateOf(false)
    private var uxMonitor: AutomotiveUxMonitor? = null
    private lateinit var model: DriveViewModel
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[DriveViewModel::class.java]
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            uxMonitor = AutomotiveUxMonitor(this) { parked = it }.also { it.start() }
        } else parked = true // Phone simulation is explicitly MOCK, never a verified vehicle state.
        setContent {
            DrivelogTheme {
                val state = model.state
                val allowed = resumed && parked
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .safeDrawingPadding().semantics { testTagsAsResourceId = true },
                ) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("DRIVELOG", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        listOf("diagnostics" to "SDK 진단", "drive" to "주행 기록", "card" to "사진 카드").forEach { (screen, title) ->
                            TextButton(onClick = { if (allowed) model.go(screen) },
                                enabled = allowed && !state.recording,
                                modifier = Modifier.testTag("tab-$screen")) { Text(title) }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when (state.screen) {
                            "drive" -> DriveScreen(state, allowed,
                                { if (allowed) model.mode(it) }, { if (allowed) model.destination(it) },
                                { if (allowed) model.start() }, { if (allowed) model.stop() },
                                { if (allowed) model.go("card") },
                                { if (allowed) model.navigation(NavigationAction.ROUTE) })
                            "card" -> DriveCardScreen(state, allowed,
                                { if (allowed) model.template(it) }, { if (parked && !state.recording) model.photo(it) },
                                model::exported, model::report)
                            else -> PleosDiagnosticsScreen(state, model.environment, allowed,
                                { if (allowed) model.vehicle() }, { if (allowed) model.location() },
                                { if (allowed) model.navigation(it) },
                                {
                                    if (allowed) {
                                        val permission = "pleos.car.permission.FUSED_LOCATION"
                                        if (runCatching { packageManager.getPermissionInfo(permission, 0) }.isSuccess)
                                            locationPermission.launch(permission)
                                        else model.report("현재 플랫폼에 FUSED_LOCATION 권한 정의가 없습니다.")
                                    }
                                })
                        }
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onDestroy() { uxMonitor?.close(); super.onDestroy() }
}
