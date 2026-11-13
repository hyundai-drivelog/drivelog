package com.drivelog.app.core.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** SDK-free build: no navigation or location, so only the labelled simulation can record. */
private class NoPleosCarPlatform(context: Context) : CarPlatform {
    private val sounds = SoundEffects(context.applicationContext)
    override val soundEffectsEnabled get() = sounds.enabled
    override fun setSoundEffectsEnabled(enabled: Boolean) { sounds.enabled = enabled }
    override fun playCue(cue: SoundCue, spokenText: String?) { sounds.play(cue) }
    override val navi: StateFlow<NaviState> = MutableStateFlow(NaviState())
    override val speech: StateFlow<SpeechState> = MutableStateFlow(SpeechState())
    override fun searchPlace(keyword: String) {}
    override fun cancelRoute() {}
    override fun clearRouteEnd() {}
    override fun startListening() {}
    override fun stopListening() {}
    override fun clearSpeech() {}
    override fun connect() {}
    override fun close() { sounds.close() }
    override suspend fun odometerRaw(): Float? = null
    override fun locationSupported() = false
    override fun locationPermissions() = emptyList<String>()
    override fun startLocation(onPoint: (Double, Double) -> Unit, onError: (String) -> Unit) = false
    override fun stopLocation() {}
    override fun watchArrival(latitude: Double, longitude: Double, radiusMeters: Float, onArrived: () -> Unit) {}
    override fun clearArrival() {}
    override fun refreshNaviPlaces() {}
    override fun requestRoute(place: NaviPlace) {}
    override fun openNavigation() {}
    override fun showRouteOverview() {}
    override fun clearRouteMessage() {}
    override fun speak(text: String) {}
}

@Suppress("UNUSED_PARAMETER")
fun createCarPlatform(context: Context): CarPlatform = NoPleosCarPlatform(context)
