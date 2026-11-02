package com.drivelog.app.data.sdk

import android.content.Context

class PleosRuntimeDataSource(@Suppress("UNUSED_PARAMETER") context: Context, private val emit: (DiagnosticResult) -> Unit) : RuntimeDataSource {
    private fun unavailable(target: String) = DiagnosticResult(target, ProbeStatus.UNAVAILABLE, "SDK 미포함 빌드").also(emit)
    override suspend fun vehicle() = listOf(unavailable("vehicle.init"))
    override suspend fun location() = unavailable("location.current")
    override suspend fun navigation(action: NavigationAction, destination: DriveDestination) = unavailable("navi.$action")
    override fun startLocations(onPoint: (GeoPoint) -> Unit, onResult: (DiagnosticResult) -> Unit): Boolean {
        onResult(unavailable("location.stream")); return false
    }
    override fun stopLocations() {}
    override fun close() {}
}
