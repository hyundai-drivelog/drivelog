package com.drivelog.app.core.drive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.vehicle.Vehicle
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/** Verified capability probe only: a trip snapshot is not a completed DRIVELOG session. */
class PleosDriveDataSource(private val context: Context) : DriveDataSource {
    override suspend fun capture(): DrivePayload {
        val fusedSupported = runCatching { FusedLocation(context).isSupported() }.getOrDefault(false)
        val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        // The action is declared in the downloaded Vehicle 2.3.2 AAR manifest.
        val service = context.packageManager.resolveService(
            Intent("ai.pleos.playground.vehicle.VEHICLE_START"), 0,
        )
        if (!automotive || service == null) {
            throw PleosUnavailableException(
                "Vehicle Service 없음 · FusedLocation 지원=$fusedSupported · NaviHelper 미연결",
            )
        }
        val vehicle = Vehicle(context.applicationContext)
        try {
            vehicle.initialize()
            withTimeout(3_000) {
                suspendCancellableCoroutine { continuation ->
                    vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities(
                        { if (continuation.isActive) continuation.resume(Unit) },
                        { if (continuation.isActive) continuation.resumeWithException(it) },
                    )
                }
            }
            // Start/end session boundaries and measurement units have not been verified on an AVD.
            // Do not manufacture a PLEOS record from partial SDK values or mix it with mock fields.
            throw PleosUnavailableException("Vehicle 응답 수신 · 완전한 주행 세션 수집은 아직 미검증")
        } catch (_: TimeoutCancellationException) {
            throw PleosUnavailableException("Vehicle 응답 시간 초과 · 실제 데이터 수신 불가")
        } finally {
            vehicle.release()
        }
    }
}
