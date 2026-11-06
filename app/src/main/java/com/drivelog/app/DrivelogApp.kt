package com.drivelog.app

import android.app.Application
import com.drivelog.app.core.journey.JourneyStore
import com.drivelog.app.core.platform.CarGear
import com.drivelog.app.core.platform.CarPlatform
import com.drivelog.app.core.platform.createCarPlatform
import com.drivelog.app.core.recording.Recorder
import com.drivelog.app.core.vehicle.VehicleStateMonitor
import com.drivelog.app.feature.car.RouteMapCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide owners, so a drive keeps recording while activities come and go. */
class DrivelogApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var platform: CarPlatform
        private set
    lateinit var vehicleState: VehicleStateMonitor
        private set
    lateinit var store: JourneyStore
        private set
    lateinit var recorder: Recorder
        private set

    lateinit var routeMaps: RouteMapCache
        private set

    /** Debug-only stand-in for the gear signal; always surfaced on screen as simulation. */
    val simulatedGear = MutableStateFlow(CarGear.PARK)

    override fun onCreate() {
        super.onCreate()
        platform = createCarPlatform(this).also { it.connect() }
        vehicleState = VehicleStateMonitor(this).also { it.start() }
        store = JourneyStore(this, BuildConfig.API_BASE_URL, scope, queueForServer = BuildConfig.PHONE_LINK_ENABLED).also { it.startSyncLoop() }
        recorder = Recorder(this, platform, scope) { simulatedGear.value }
        routeMaps = RouteMapCache(this)
    }
}
