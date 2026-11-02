package com.drivelog.app.data.sdk

import android.content.Context
import android.content.pm.PackageManager
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Public signatures checked against the installed SDK artifacts and Pleos documentation. */
class PleosRuntimeDataSource(private val context: Context, private val emit: (DiagnosticResult) -> Unit) : RuntimeDataSource {
    private var vehicleSdk: Vehicle? = null
    private var fused: FusedLocation? = null
    private var navi: NaviHelper? = null
    private var naviReady = false
    private var stateReply: CompletableDeferred<String>? = null
    private var routeReply: CompletableDeferred<String>? = null
    private var pendingDestination: DriveDestination? = null
    private var routeGuiding = false
    private var streamListener: FusedLocationListener? = null
    private val automotive get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    private fun result(target: String, status: ProbeStatus, message: String, code: String? = null) =
        DiagnosticResult(target, status, message, code).also(emit)

    private fun failure(target: String, error: Throwable): DiagnosticResult {
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
        // Allowlist copied from the installed vehicle-data public ErrorCode enum. That artifact is
        // runtime-only, so avoid coupling the app's compile classpath to an obfuscated exception class.
        val nativeCode = setOf("ERROR_INTERNAL_SERVICE", "ERROR_SERVICE_CONNECTION_FAILURE", "ERROR_DRIVING_RESTRICTED",
            "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_VALUE_TYPE", "ERROR_NOT_SUPPORTED_AREA",
            "ERROR_EMPTY_RESULT", "ERROR_SECURITY_PERMISSION", "ERROR_UNDEFINED")
            .firstOrNull { error.message?.contains(it) == true }
        val status = when {
            nativeCode == "ERROR_SECURITY_PERMISSION" -> ProbeStatus.PERMISSION_DENIED
            nativeCode?.startsWith("ERROR_NOT_SUPPORTED_") == true -> ProbeStatus.UNSUPPORTED
            nativeCode == "ERROR_EMPTY_RESULT" || nativeCode == "ERROR_SERVICE_CONNECTION_FAILURE" -> ProbeStatus.UNAVAILABLE
            else -> when (error) {
            is SecurityException -> ProbeStatus.PERMISSION_DENIED
            is UnsupportedOperationException -> ProbeStatus.UNSUPPORTED
            is TimeoutCancellationException -> ProbeStatus.UNAVAILABLE
            else -> ProbeStatus.ERROR
            }
        }
        // No SDK credentials are passed to this class. Do not log arbitrary exception bodies.
        return result(target, status, "호출 실패 · ${nativeCode ?: error.javaClass.simpleName}", nativeCode ?: error.javaClass.name)
    }

    private suspend fun <T> read(block: ((T) -> Unit, (Exception) -> Unit) -> Unit): T = withTimeout(4000) {
        suspendCancellableCoroutine { continuation ->
            block({ if (continuation.isActive) continuation.resume(it) },
                { if (continuation.isActive) continuation.resumeWithException(it) })
        }
    }

    private suspend fun <T> probe(target: String, permission: String,
            capability: ((CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit,
            query: ((T?) -> Unit, (Exception) -> Unit) -> Unit,
            format: (T) -> String): DiagnosticResult {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            return result(target, ProbeStatus.PERMISSION_DENIED, "필요 권한 미허용: ${permission.substringAfterLast('.')}")
        return try {
            val config = read(capability)
            if (config.accessType != AccessType.READ && config.accessType != AccessType.READ_WRITE)
                result(target, ProbeStatus.UNSUPPORTED, "읽기 미지원 · access=${config.accessType}")
            else {
                val value = read(query)
                if (value == null) result(target, ProbeStatus.UNAVAILABLE, "실제 callback 수신 · 값 null")
                else result(target, ProbeStatus.SUCCESS, format(value))
            }
        } catch (error: Exception) { failure(target, error) }
    }

    override suspend fun vehicle(): List<DiagnosticResult> {
        if (!automotive) return listOf(result("vehicle.init", ProbeStatus.UNAVAILABLE, "일반 Android 환경 · Vehicle Service 없음"))
        return try {
            val v = vehicleSdk ?: Vehicle(context.applicationContext).also {
                it.initialize(); vehicleSdk = it
                result("vehicle.init", ProbeStatus.UNAVAILABLE, "initialize 호출 반환 · 서비스 응답 확인 중")
            }
            val values = listOf(
                probe<String>("vehicle.info", "pleos.car.permission.CAR_INFO", v.getCarInfo()::checkVINInfoCapabilities,
                    v.getCarInfo()::getVINInfo) { "VIN 수신 · ${it.length}자 (식별값 비공개)" },
                probe<ai.pleos.playground.vehicle.constant.control.DrivingModeType>("vehicle.drivingMode", "pleos.car.permission.CAR_DRIVE",
                    v.getDrivingMode()::checkDrivingModeStateCapabilities, v.getDrivingMode()::getCurrentDrivingModeState) { "드라이브 모드: $it (이동 여부와 별개)" },
                probe<Float>("vehicle.odometer", "pleos.car.permission.CAR_DRIVE", v.getOdometer()::checkCurrentDistanceDrivenCapabilities,
                    v.getOdometer()::getCurrentDistanceDriven) { "원시 주행거리: $it · 단위/세션 기준 별도 검증" },
                probe<Int>("vehicle.fuel", "pleos.car.permission.CAR_ENERGY", v.getFuel()::checkFuelPercentCapabilities,
                    v.getFuel()::getFuelPercent) { "연료 잔량 원시값: $it" },
                probe<Int>("vehicle.battery", "pleos.car.permission.CAR_ENERGY", v.getEvBattery()::checkEvBatteryStateOfChargeCapabilities,
                    v.getEvBattery()::getEvBatteryStateOfCharge) { "배터리 SOC 원시값: $it" },
                probe<Float>("vehicle.fuelEfficiency", "pleos.car.permission.CAR_ENERGY", v.getFuel()::checkCurrentFuelEconomyCapabilities,
                    v.getFuel()::getCurrentFuelEconomy) { "현재 트립 연비 원시값: $it · 단위 미확인" },
                probe<Float>("vehicle.evEfficiency", "pleos.car.permission.CAR_ENERGY", v.getEvBattery()::checkCurrentEvEfficiencyCapabilities,
                    v.getEvBattery()::getCurrentEvEfficiency) { "현재 트립 전비 원시값: $it · 단위 미확인" },
                result("vehicle.speed", ProbeStatus.UNSUPPORTED, "검증된 현재 속도 accessor 없음 · 크루즈 목표 속도로 대체하지 않음"),
            )
            if (values.any { it.status == ProbeStatus.SUCCESS }) result("vehicle.init", ProbeStatus.SUCCESS, "실제 SDK 응답 수신")
            values
        } catch (error: Exception) { listOf(failure("vehicle.init", error)) }
    }

    private fun locationSdk(): FusedLocation? {
        val sdk = fused ?: FusedLocation(context.applicationContext).also { fused = it }
        if (!sdk.isSupported()) { result("location.support", ProbeStatus.UNSUPPORTED, "FusedLocation.isSupported=false"); return null }
        result("location.support", ProbeStatus.SUCCESS, "FusedLocation.isSupported=true")
        if (context.checkSelfPermission("pleos.car.permission.FUSED_LOCATION") != PackageManager.PERMISSION_GRANTED) {
            result("location.permission", ProbeStatus.PERMISSION_DENIED, "FUSED_LOCATION 런타임 권한 필요"); return null
        }
        return sdk
    }

    override suspend fun location(): DiagnosticResult = try {
        val sdk = locationSdk()
        if (sdk == null) result("location.current", ProbeStatus.UNAVAILABLE, "SDK 지원 또는 권한이 준비되지 않음")
        else {
            sdk.initialize()
            val point = withTimeout(6000) { suspendCancellableCoroutine<LocationData?> { continuation ->
                sdk.getLastKnownLocation(object : FusedLocationListener {
                    override fun onChanged(location: LocationData?) { if (continuation.isActive) continuation.resume(location) }
                    override fun onError(exception: Exception) { if (continuation.isActive) continuation.resumeWithException(exception) }
                })
            } }
            if (point == null) result("location.current", ProbeStatus.UNAVAILABLE, "위치 callback 수신 · 값 null")
            else result("location.current", ProbeStatus.SUCCESS,
                "${point.latitude}, ${point.longitude} · confidence=${point.confidence} (정확도 m로 해석하지 않음) · timestamp=${point.timestampMillis}")
        }
    } catch (error: Exception) { failure("location.current", error) }

    override fun startLocations(onPoint: (GeoPoint) -> Unit, onResult: (DiagnosticResult) -> Unit): Boolean = try {
        stopLocations()
        val sdk = locationSdk()
        if (sdk == null) false else {
            sdk.initialize()
            val listener = object : FusedLocationListener {
                private var lastReceivedAt = 0L
                override fun onChanged(location: LocationData?) {
                    if (location != null) {
                        val receivedAt = System.currentTimeMillis()
                        val interval = if (lastReceivedAt == 0L) "첫 수신" else "관측 간격 ${receivedAt - lastReceivedAt} ms"
                        lastReceivedAt = receivedAt
                        onPoint(GeoPoint(location.latitude, location.longitude, receivedAt, RecordingMode.PLEOS, location.confidence))
                        onResult(DiagnosticResult("location.stream", ProbeStatus.SUCCESS, "실제 SDK 좌표 · $interval · timestamp=${location.timestampMillis} · 정확도 m 미제공"))
                    } else onResult(DiagnosticResult("location.stream", ProbeStatus.UNAVAILABLE, "위치 업데이트 null"))
                }
                override fun onError(exception: Exception) { onResult(failure("location.stream", exception)) }
            }
            streamListener = listener
            sdk.registerFusedLocationCallback(listener)
            onResult(DiagnosticResult("location.stream", ProbeStatus.UNAVAILABLE, "등록 완료 · 실제 좌표 callback 대기"))
            true
        }
    } catch (error: Exception) { onResult(failure("location.stream", error)); false }

    override fun stopLocations() {
        streamListener?.let { listener -> runCatching { fused?.unregisterFusedLocationCallback(listener) } }
        streamListener = null
    }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            val text = "mode=${routeStateInfo.naviMode}, routing=${routeStateInfo.isRouting}, terms=${routeStateInfo.termsAgreement}"
            stateReply?.complete(text); result("navi.state.event", ProbeStatus.SUCCESS, text)
            routeGuiding = routeStateInfo.naviMode.name == "ROUTE_GUIDING" && !routeStateInfo.isRouting
            if (routeGuiding && pendingDestination != null) navi?.getDestinationInfo()
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val requested = pendingDestination ?: return
            val matches = kotlin.math.abs(destinationInfo.latitude - requested.latitude) < 0.0001 &&
                kotlin.math.abs(destinationInfo.longitude - requested.longitude) < 0.0001
            result("navi.destination.info", ProbeStatus.SUCCESS, "현재 목적지: ${destinationInfo.name} · ${destinationInfo.latitude}, ${destinationInfo.longitude} · 요청 좌표 일치=$matches")
            if (matches && routeGuiding) {
                routeReply?.complete("요청 목적지 좌표 일치 + ROUTE_GUIDING 확인")
                result("navi.route", ProbeStatus.SUCCESS, "실제 목적지 일치 및 경로 안내 상태 확인")
                pendingDestination = null
            }
        }
        override fun onRoutingCompleted() { routeReply?.complete("onRoutingCompleted"); result("navi.route.event", ProbeStatus.SUCCESS, "경로 탐색 완료 이벤트") }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { result("navi.arrived.event", ProbeStatus.SUCCESS, "목적지 도착 이벤트 수신") }
        override fun onDestinationChanged() { result("navi.destination.event", ProbeStatus.SUCCESS, "목적지 변경 이벤트 수신") }
        override fun onReRouteEnded() { result("navi.reroute.event", ProbeStatus.SUCCESS, "재탐색 종료 이벤트 수신") }
        override fun onError(errorCode: NaviErrorCode) {
            result("navi.error.event", ProbeStatus.ERROR, "Navigation 앱 오류", errorCode.name)
            routeReply?.completeExceptionally(IllegalStateException(errorCode.name))
        }
    }

    private suspend fun readyNavi(): NaviHelper {
        val helper = navi ?: NaviHelper(context.applicationContext).also { navi = it }
        if (!naviReady) {
            withTimeout(8000) { suspendCancellableCoroutine<Unit> { continuation ->
                helper.initialize({ naviReady = true; if (continuation.isActive) continuation.resume(Unit) },
                    { if (continuation.isActive) continuation.resumeWithException(it) })
            } }
            helper.addListener(listener)
            result("navi.init", ProbeStatus.SUCCESS, "onConnected callback 수신")
        }
        return helper
    }

    override suspend fun navigation(action: NavigationAction, destination: DriveDestination): DiagnosticResult {
        if (!automotive) return result("navi.init", ProbeStatus.UNAVAILABLE, "일반 Android 환경 · Navi Service 미준비")
        return try {
            val helper = readyNavi()
            when (action) {
                NavigationAction.STATE -> {
                    result("navi.capability", ProbeStatus.UNSUPPORTED, "별도 capability API 없음 · 권한과 실제 응답으로 확인")
                    val reply = CompletableDeferred<String>(); stateReply = reply
                    helper.getRouteStateInfo()
                    result("navi.state", ProbeStatus.SUCCESS, withTimeout(8000) { reply.await() })
                }
                NavigationAction.ROUTE -> {
                    val reply = CompletableDeferred<String>(); routeReply = reply
                    pendingDestination = destination; routeGuiding = false
                    helper.requestRoute(RouteInfo(latitude = destination.latitude, longitude = destination.longitude,
                        poiName = destination.name, poiId = destination.poiId, poiSubId = "0", address = destination.address))
                    result("navi.route", ProbeStatus.SUCCESS, withTimeout(30000) { reply.await() })
                }
                NavigationAction.FOREGROUND -> {
                    helper.requestNavigationScreen(NavigationScreenRequest.Foreground)
                    result("navi.foreground", ProbeStatus.SUCCESS, "전환 요청 메서드 반환 · 실제 화면 표시는 별도 시각 검증 필요")
                }
            }
        } catch (error: Exception) { failure("navi.${action.name.lowercase()}", error) }
    }

    override fun close() {
        stopLocations()
        runCatching { fused?.release() }; runCatching { vehicleSdk?.release() }
        runCatching { navi?.removeListener(listener) }; runCatching { navi?.release() }
    }
}
