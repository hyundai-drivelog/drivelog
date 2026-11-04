package com.drivelog.app.core.platform

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class PleosCarPlatform(context: Context) : CarPlatform {
    private val context = context.applicationContext
    private val mutableNavi = MutableStateFlow(NaviState())
    override val navi: StateFlow<NaviState> = mutableNavi
    private val mutableSpeech = MutableStateFlow(SpeechState())
    override val speech: StateFlow<SpeechState> = mutableSpeech
    private var stt: SpeechToText? = null

    private val handler = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var fused: FusedLocation? = null
    private var helper: NaviHelper? = null
    private var naviConnected = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var locationListener: FusedLocationListener? = null
    private var alertId: String? = null
    private var onArrived: (() -> Unit)? = null
    private var replacingRoute = false

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val speechFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAttributes)
        .build()
    private val listenFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .build()

    private val naviListener = object : NaviHelperEventListener {
        override fun onBookmarkInfo(bookmarkInfo: List<BookmarkInfo>) {
            mutableNavi.update { state -> state.copy(bookmarks = bookmarkInfo.map(::fromBookmark)) }
        }
        override fun onRecentDestinationInfo(recentDestinationInfo: List<RecentDestinationInfo>) {
            mutableNavi.update { state -> state.copy(recents = recentDestinationInfo.map(::fromRecent)) }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            mutableNavi.update { state -> state.copy(currentDestination = fromDestination(destinationInfo)) }
        }
        override fun onBookMarkChanged() { runCatching { helper?.getBookmarkInfo() } }
        override fun onDestinationChanged() { runCatching { helper?.getDestinationInfo() } }
        // Side by side the app never returns to the foreground, and the navigation app was seen sending only these.
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(TAG, "route event: started")
            replacingRoute = false
            mutableNavi.update { it.copy(routeEnd = null) }
            route("내비에서 경로를 찾고 있어요")
            runCatching { helper?.getDestinationInfo() }
        }
        override fun onRoutingCompleted() {
            Log.i(TAG, "route event: routing completed")
            route("내비 안내를 시작했어요")
            runCatching { helper?.getDestinationInfo() }
        }
        override fun onRouteCancelled() {
            Log.i(TAG, "route event: cancelled, replacing=$replacingRoute")
            // Our own new route replacing an old one also cancels it. That is not the driver ending guidance.
            val end = if (replacingRoute) null else RouteEnd.STOPPED
            mutableNavi.update { state -> state.copy(currentDestination = null, routeMessage = null, routeEnd = state.routeEnd ?: end) }
        }
        // Not onRouteEnded: the navigation app sends it right after onRouteStarted, while guidance is starting.
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.i(TAG, "route event: arrived")
            onArrived?.invoke()
            mutableNavi.update { it.copy(routeEnd = RouteEnd.ARRIVED) }
        }
        override fun onError(errorCode: NaviErrorCode) {
            replacingRoute = false
            route(when (errorCode) {
                NaviErrorCode.CAUSE_LOW_BATTERY -> "배터리가 부족해 경로를 찾을 수 없어요"
                NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> "탐색 가능한 거리를 넘었어요"
                NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> "목적지가 너무 가까워요"
                else -> "경로를 찾지 못했어요"
            })
        }
    }

    private fun route(message: String) = mutableNavi.update { it.copy(routeMessage = message) }

    private fun fromBookmark(info: BookmarkInfo) = NaviPlace(info.name, info.address, info.latitude, info.longitude, info.poiId, info.poiSubId, info.poiProvider)
    private fun fromRecent(info: RecentDestinationInfo) = NaviPlace(info.name, info.address, info.latitude, info.longitude, info.poiId, info.poiSubId, info.poiProvider)
    private fun fromDestination(info: DestinationInfo) = NaviPlace(info.name, info.address, info.latitude, info.longitude, info.poiId, info.poiSubId, info.poiProvider)

    private fun serviceExists(action: String) = context.packageManager.resolveService(Intent(action), 0) != null

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Each service is optional: model availability differs, so a missing one only hides its feature. */
    override fun connect() {
        if (vehicle == null && serviceExists(VEHICLE_ACTION)) {
            runCatching { vehicle = Vehicle(context).also { it.initialize() } }
                .onFailure { Log.w(TAG, "vehicle unavailable: ${it.javaClass.simpleName}") }
        }
        if (helper == null && serviceExists(NAVI_ACTION)) {
            runCatching {
                val sdk = NaviHelper(context)
                helper = sdk
                sdk.initialize({
                    naviConnected = true
                    sdk.addListener(naviListener)
                    mutableNavi.update { it.copy(available = true) }
                    refreshNaviPlaces()
                }, { failure ->
                    Log.w(TAG, "navi connection failed: ${failure.javaClass.simpleName}")
                    mutableNavi.update { it.copy(available = false) }
                })
            }.onFailure { Log.w(TAG, "navi unavailable: ${it.javaClass.simpleName}") }
        }
        if (tts == null && serviceExists(SPEECH_ACTION)) {
            runCatching {
                val engine = TextToSpeech(context, Mode.ON_DEVICE)
                engine.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        ttsReady = true
                        runCatching {
                            engine.setLocale(Locale.KOREA) // The engine rejects a language without a region ("ko-").
                            engine.setAudioAttributes(speechAttributes)
                            engine.addEventListener(speechEvents)
                        }
                    }
                    override fun onFailure(errorType: ErrorType) { Log.w(TAG, "tts init failed: $errorType") }
                })
                tts = engine
            }.onFailure { Log.w(TAG, "tts unavailable: ${it.javaClass.simpleName}") }
        }
        if (stt == null && serviceExists(SPEECH_ACTION)) {
            runCatching {
                val engine = SpeechToText(context, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
                engine.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                    override fun onSuccess() {
                        runCatching {
                            engine.setLocale(Locale.KOREA)
                            engine.addListener(sttListener)
                            mutableSpeech.update { it.copy(available = true) }
                        }.onFailure { Log.w(TAG, "stt setup failed: ${it.javaClass.simpleName}") }
                    }
                    override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) { Log.w(TAG, "stt init failed: $errorType") }
                })
                stt = engine
            }.onFailure { Log.w(TAG, "stt unavailable: ${it.javaClass.simpleName}") }
        }
    }

    private var peakRms = Double.NEGATIVE_INFINITY

    private val sttListener = object : ResultListener {
        override fun onReady() {
            if (!mutableSpeech.value.listening) return // Stopped while the engine was still starting.
            mutableSpeech.update { it.copy(ready = true) }
            // The driver's time to speak starts now, not at the tap.
            handler.removeCallbacks(listenTimeout)
            handler.postDelayed(listenTimeout, LISTEN_TIMEOUT_MS)
        }
        override fun onStartedRecognition() {}
        override fun onUpdatedEpdData(on: Long, off: Long) {}
        override fun onUpdatedRms(rms: Double) { if (rms > peakRms) peakRms = rms }
        override fun onUpdated(stt: String, completed: Boolean) {
            if (completed && stt.isNotBlank()) handler.removeCallbacks(listenTimeout)
            mutableSpeech.update { state ->
                if (completed && stt.isNotBlank()) state.copy(listening = false, partial = "", heard = stt.trim())
                else state.copy(partial = stt)
            }
        }
        override fun onEndedRecognition() {
            Log.d(TAG, "stt ended, peak rms $peakRms") // Tells a silent microphone from an engine that heard nothing.
            handler.removeCallbacks(listenTimeout)
            audioManager.abandonAudioFocusRequest(listenFocus)
            mutableSpeech.update { state ->
                if (!state.listening) return@update state // Already consumed, or stopped by the driver.
                // The docs do not promise a completed result before the end, so the last partial still counts.
                val text = state.partial.trim().ifEmpty { null }
                state.copy(listening = false, partial = "", heard = text, message = if (text == null) "잘 듣지 못했어요. 다시 말씀해 주세요" else null)
            }
        }
        override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) {
            Log.w(TAG, "stt error: $errorType")
            handler.removeCallbacks(listenTimeout)
            audioManager.abandonAudioFocusRequest(listenFocus)
            mutableSpeech.update { it.copy(listening = false, partial = "", message = "음성을 인식하지 못했어요. 직접 입력해 주세요") }
        }
    }

    /** The microphone must not stay open when the engine never decides the phrase has ended. */
    private val listenTimeout = Runnable {
        val state = mutableSpeech.value
        val partial = state.partial.trim()
        Log.d(TAG, "stt timed out, ready ${state.ready}, peak rms $peakRms")
        stopListening()
        mutableSpeech.update {
            when {
                partial.isNotEmpty() -> it.copy(heard = partial)
                !state.ready -> it.copy(message = "음성 인식을 시작하지 못했어요. 직접 입력해 주세요")
                else -> it.copy(message = "잘 듣지 못했어요. 다시 말씀해 주세요")
            }
        }
    }

    override fun startListening() {
        val engine = stt?.takeIf { mutableSpeech.value.available } ?: return
        if (audioManager.requestAudioFocus(listenFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mutableSpeech.update { it.copy(message = "다른 오디오가 사용 중이에요. 잠시 후 다시 시도해 주세요") }
            return
        }
        peakRms = Double.NEGATIVE_INFINITY
        mutableSpeech.update { it.copy(listening = true, ready = false, partial = "", heard = null, message = null) }
        handler.postDelayed(listenTimeout, LISTEN_PREPARE_TIMEOUT_MS)
        runCatching { engine.request() }.onFailure {
            handler.removeCallbacks(listenTimeout)
            Log.w(TAG, "stt request failed: ${it.javaClass.simpleName}")
            audioManager.abandonAudioFocusRequest(listenFocus)
            mutableSpeech.update { state -> state.copy(listening = false, message = "음성을 인식하지 못했어요. 직접 입력해 주세요") }
        }
    }

    override fun stopListening() {
        handler.removeCallbacks(listenTimeout)
        if (!mutableSpeech.value.listening) return
        runCatching { stt?.stop() }
        audioManager.abandonAudioFocusRequest(listenFocus)
        mutableSpeech.update { it.copy(listening = false, partial = "") }
    }

    override fun clearSpeech() = mutableSpeech.update { it.copy(heard = null, message = null) }

    override fun cancelRoute() {
        val sdk = helper?.takeIf { naviConnected } ?: return
        runCatching { sdk.cancelRoute() }.onFailure {
            Log.w(TAG, "route cancel failed: ${it.javaClass.simpleName}")
            route("안내를 끄지 못했어요")
        }
    }

    override fun searchPlace(keyword: String) {
        val sdk = helper?.takeIf { naviConnected } ?: run { route("내비게이션에 연결할 수 없어요"); return }
        runCatching { sdk.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword)) }.onFailure {
            Log.w(TAG, "navi search failed: ${it.javaClass.simpleName}")
            route("내비 검색을 열지 못했어요")
        }
    }

    override fun close() {
        stopLocation()
        clearArrival()
        runCatching { vehicle?.release() }
        runCatching { fused?.release() }
        runCatching { helper?.removeListener(naviListener) }
        runCatching { helper?.release() }
        runCatching { tts?.removeEventListener(speechEvents) }
        runCatching { tts?.release() }
        runCatching { stt?.removeListener(sttListener) }
        runCatching { stt?.release() }
        audioManager.abandonAudioFocusRequest(speechFocus)
        audioManager.abandonAudioFocusRequest(listenFocus)
        vehicle = null; fused = null; helper = null; tts = null; ttsReady = false; naviConnected = false; stt = null
        mutableSpeech.value = SpeechState()
    }

    override suspend fun odometerRaw(): Float? {
        val odometer = vehicle?.getOdometer() ?: return null
        if (!granted(CAR_DRIVE)) return null
        return withTimeoutOrNull(3_000) {
            val supported = suspendCancellableCoroutine { continuation ->
                runCatching {
                    odometer.checkCurrentDistanceDrivenCapabilities(
                        { if (continuation.isActive) continuation.resume(true) },
                        { if (continuation.isActive) continuation.resume(false) },
                    )
                }.onFailure { if (continuation.isActive) continuation.resume(false) }
            }
            if (!supported) return@withTimeoutOrNull null
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    odometer.getCurrentDistanceDriven(
                        { value -> if (continuation.isActive) continuation.resume(value) },
                        { if (continuation.isActive) continuation.resume(null) },
                    )
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }
    }

    private fun locationSdk(): FusedLocation? = fused ?: runCatching { FusedLocation(context) }.getOrNull()?.also { fused = it }

    override fun locationSupported() = runCatching { locationSdk()?.isSupported() == true }.getOrDefault(false)

    override fun locationPermissions() = listOf(FUSED_LOCATION, "android.permission.ACCESS_FINE_LOCATION")
        .filter { permission -> runCatching { context.packageManager.getPermissionInfo(permission, 0) }.isSuccess }

    override fun startLocation(onPoint: (Double, Double) -> Unit, onError: (String) -> Unit): Boolean = try {
        stopLocation()
        val sdk = locationSdk()
        if (sdk == null || !sdk.isSupported() || !granted(FUSED_LOCATION)) false
        else {
            sdk.initialize()
            val listener = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    if (location != null) onPoint(location.latitude, location.longitude)
                }
                override fun onError(exception: Exception) { onError(exception.javaClass.simpleName) }
            }
            sdk.registerFusedLocationCallback(listener)
            locationListener = listener
            true
        }
    } catch (error: Exception) {
        onError(error.javaClass.simpleName)
        false
    }

    override fun stopLocation() {
        locationListener?.let { listener -> runCatching { fused?.unregisterFusedLocationCallback(listener) } }
        locationListener = null
    }

    override fun watchArrival(latitude: Double, longitude: Double, radiusMeters: Float, onArrived: () -> Unit) {
        clearArrival()
        this.onArrived = onArrived
        val sdk = fused ?: return
        runCatching {
            sdk.addProximityAlert(
                ProximityAlertRequest(latitude, longitude, radiusMeters, setOf(ProximityTransition.ENTER)),
                object : ProximityAlertListener {
                    override fun onRegistered(id: String) { alertId = id }
                    override fun onTransition(event: ProximityAlertEvent) { this@PleosCarPlatform.onArrived?.invoke() }
                    override fun onError(exception: Exception) { Log.w(TAG, "proximity failed: ${exception.javaClass.simpleName}") }
                },
            )
        }.onFailure { Log.w(TAG, "proximity unavailable: ${it.javaClass.simpleName}") }
    }

    override fun clearRouteEnd() = mutableNavi.update { it.copy(routeEnd = null) }

    override fun clearArrival() {
        alertId?.let { id -> runCatching { fused?.removeProximityAlert(id) } }
        alertId = null
        onArrived = null
    }

    override fun refreshNaviPlaces() {
        val sdk = helper?.takeIf { naviConnected } ?: return
        runCatching {
            sdk.getBookmarkInfo()
            sdk.getRecentDestinationInfo()
            sdk.getDestinationInfo()
        }.onFailure { Log.w(TAG, "navi query failed: ${it.javaClass.simpleName}") }
    }

    override fun requestRoute(place: NaviPlace) {
        val sdk = helper?.takeIf { naviConnected } ?: run { route("내비게이션에 연결할 수 없어요"); return }
        replacingRoute = mutableNavi.value.currentDestination != null
        runCatching {
            sdk.requestRoute(RouteInfo(
                latitude = place.latitude, longitude = place.longitude, poiName = place.name,
                poiId = place.poiId ?: "", poiSubId = place.poiSubId ?: "0", address = place.address,
                poiProvider = place.poiProvider ?: "",
            ))
            route("내비에 목적지를 보냈어요")
        }.onFailure {
            Log.w(TAG, "route request failed: ${it.javaClass.simpleName}")
            route("경로를 요청하지 못했어요")
        }
    }

    override fun openNavigation() {
        val sdk = helper?.takeIf { naviConnected } ?: return
        runCatching { sdk.requestNavigationScreen(NavigationScreenRequest.Foreground) }
            .onFailure { Log.w(TAG, "navi screen failed: ${it.javaClass.simpleName}") }
    }

    override fun clearRouteMessage() = mutableNavi.update { it.copy(routeMessage = null) }

    private val speechEvents = object : EventListener {
        override fun onStart() {}
        override fun onReady() {}
        override fun onDone() { audioManager.abandonAudioFocusRequest(speechFocus) }
        override fun onError(errorType: ErrorType) { audioManager.abandonAudioFocusRequest(speechFocus) }
        override fun onStop() { audioManager.abandonAudioFocusRequest(speechFocus) }
        override fun onUpdatedRms(rms: Double) {}
    }

    /** Focus first, then a short lead-in: the SDK exposes no PCM to pad, so this is the guide's fallback. */
    override fun speak(text: String) {
        if (!ttsReady) return
        if (audioManager.requestAudioFocus(speechFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        handler.postDelayed({
            runCatching { tts?.speak(text, true) }.onFailure {
                Log.w(TAG, "tts failed: ${it.javaClass.simpleName}")
                audioManager.abandonAudioFocusRequest(speechFocus)
            }
        }, SPEECH_LEAD_IN_MS)
    }

    private companion object {
        const val TAG = "DriveLogPlatform"
        const val CAR_DRIVE = "pleos.car.permission.CAR_DRIVE"
        const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        const val VEHICLE_ACTION = "ai.pleos.playground.vehicle.VEHICLE_START"
        const val NAVI_ACTION = "ai.pleos.playground.intent.action.NAVI_START"
        const val SPEECH_ACTION = "ai.pleos.playground.caas.intent.action.SPEECH_START"
        const val SPEECH_LEAD_IN_MS = 150L
        const val LISTEN_TIMEOUT_MS = 10_000L
        const val LISTEN_PREPARE_TIMEOUT_MS = 20_000L // A cold engine took about 9 seconds on the emulator.
    }
}

fun createCarPlatform(context: Context): CarPlatform = PleosCarPlatform(context)
