package com.drivelog.app.data.sdk

import android.content.Context
import android.content.pm.PackageManager
import java.time.Instant

enum class ProbeStatus { SUCCESS, UNSUPPORTED, PERMISSION_DENIED, UNAVAILABLE, ERROR }
enum class RecordingMode { MOCK, PLEOS }
enum class NavigationAction { STATE, ROUTE, FOREGROUND }

data class DiagnosticResult(
    val target: String,
    val status: ProbeStatus,
    val message: String,
    val code: String? = null,
    val at: String = Instant.now().toString(),
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val receivedAtMillis: Long,
    val source: RecordingMode,
    val confidence: Float? = null,
    val accuracyMeters: Float? = null,
)

data class DriveDestination(val name: String, val latitude: Double, val longitude: Double, val poiId: String, val address: String)

val demoDestinations = listOf(
    DriveDestination("42dot · 공식 예제", 37.48544722, 127.03636666, "36khp37hh8gqcm5nqhe5", "서울시 강남구 남부순환로 2621"),
    DriveDestination("서울숲 · 데모 목적지", 37.5445, 127.0374, "", "서울숲 데모 좌표"),
    DriveDestination("광안리 해수욕장", 35.1532, 129.1186, "", "부산 수영구"),
    DriveDestination("송정 해수욕장", 35.1786, 129.1996, "", "부산 해운대구"),
    DriveDestination("다대포 해수욕장", 35.0466, 128.9662, "", "부산 사하구"),
)

interface RuntimeDataSource {
    suspend fun vehicle(): List<DiagnosticResult>
    suspend fun location(): DiagnosticResult
    suspend fun navigation(action: NavigationAction, destination: DriveDestination): DiagnosticResult
    fun startLocations(onPoint: (GeoPoint) -> Unit, onResult: (DiagnosticResult) -> Unit): Boolean
    fun stopLocations()
    fun close()
}

val sdkPermissions = listOf(
    "pleos.car.permission.CAR_INFO", "pleos.car.permission.CAR_DRIVE",
    "pleos.car.permission.CAR_ENERGY", "pleos.car.permission.FUSED_LOCATION",
    "pleos.car.permission.NAVI_ROUTE", "pleos.car.permission.NAVI_ROUTE_SEARCH",
    "pleos.car.permission.NAVI_CUSTOM_MAP",
)

fun permissionResults(context: Context): List<DiagnosticResult> = sdkPermissions.map { permission ->
    val declared = runCatching { context.packageManager.getPermissionInfo(permission, 0) }.isSuccess
    val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    DiagnosticResult("permission.${permission.substringAfterLast('.')}",
        if (!declared) ProbeStatus.UNAVAILABLE else if (granted) ProbeStatus.SUCCESS else ProbeStatus.PERMISSION_DENIED,
        if (!declared) "플랫폼에 권한 정의 없음" else if (granted) "허용됨" else "권한 미허용")
}
