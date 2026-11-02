package com.drivelog.app.core.platform

import kotlinx.coroutines.flow.StateFlow

enum class CarGear { UNKNOWN, PARK, REVERSE, NEUTRAL, DRIVE }

/** A place as the navigation app knows it; its POI fields are passed back unchanged when requesting a route. */
data class NaviPlace(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String? = null,
    val poiSubId: String? = null,
    val poiProvider: String? = null,
)

data class NaviState(
    val available: Boolean = false,
    val bookmarks: List<NaviPlace> = emptyList(),
    val recents: List<NaviPlace> = emptyList(),
    val currentDestination: NaviPlace? = null,
    val routeMessage: String? = null,
)

/** Pleos SDK surface used by the app. The noPleos build supplies a stub that reports everything unavailable. */
interface CarPlatform {
    val navi: StateFlow<NaviState>

    fun connect()
    fun close()

    /** Raw Vehicle SDK reading after a capability check. The unit is unverified, so callers cross-check it. */
    suspend fun odometerRaw(): Float?

    fun locationSupported(): Boolean
    fun locationPermissions(): List<String>
    fun startLocation(onPoint: (latitude: Double, longitude: Double) -> Unit, onError: (String) -> Unit): Boolean
    fun stopLocation()

    /** FusedLocation proximity alert and the NaviHelper arrival event both land here. */
    fun watchArrival(latitude: Double, longitude: Double, radiusMeters: Float, onArrived: () -> Unit)
    fun clearArrival()

    /** Refreshes bookmarks, recent destinations and the current destination from the navigation app. */
    fun refreshNaviPlaces()

    /** Sent only on an explicit user action. The result arrives through [NaviState.routeMessage]. */
    fun requestRoute(place: NaviPlace)
    fun openNavigation()
    fun clearRouteMessage()

    /** Gleo AI on-device TTS. A short spoken cue keeps the driver's eyes on the road; failure is silent. */
    fun speak(text: String)
}
