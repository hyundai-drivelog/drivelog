package com.drivelog.app.core.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Standard AAOS API, separate from Pleos SDK. Unknown/disconnected means locked. */
class AutomotiveUxMonitor(private val context: Context, private val onAllowed: (Boolean) -> Unit) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start() {
        onAllowed(false)
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (!ready) {
                    manager = null
                    onAllowed(false)
                } else {
                    try {
                        manager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                        manager?.registerListener { onAllowed(!it.isRequiresDistractionOptimization) }
                        onAllowed(manager?.currentCarUxRestrictions?.isRequiresDistractionOptimization == false)
                    } catch (_: Exception) { onAllowed(false) }
                }
            }
        } catch (_: Exception) { onAllowed(false) }
    }

    fun close() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
        onAllowed(false)
    }
}
