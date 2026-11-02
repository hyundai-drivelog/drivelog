package com.drivelog.app.core.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.drivelog.app.core.platform.CarGear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Standard AAOS signals: driving restrictions and gear. Pleos policy routes standard data through the Car API,
 * and the SDK's own gear call is deprecated. Anything not yet known reads as restricted.
 */
class VehicleStateMonitor(context: Context) {
    private val context = context.applicationContext
    private val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private val mutableUxAllowed = MutableStateFlow(!automotive)
    private val mutableGear = MutableStateFlow(CarGear.UNKNOWN)
    val uxAllowed: StateFlow<Boolean> = mutableUxAllowed
    val gear: StateFlow<CarGear> = mutableGear

    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var propertyManager: CarPropertyManager? = null

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) { (value.value as? Int)?.let { mutableGear.value = fromAaos(it) } }
        override fun onErrorEvent(propertyId: Int, zone: Int) { mutableGear.value = CarGear.UNKNOWN }
    }

    fun start() {
        if (!automotive || car != null) return
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (ready) attach(connected) else detach()
            }
        } catch (error: Exception) {
            Log.w(TAG, "car service unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun attach(connected: Car) {
        try {
            uxManager = (connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager)?.also { manager ->
                manager.registerListener { mutableUxAllowed.value = !it.isRequiresDistractionOptimization }
                mutableUxAllowed.value = !manager.currentCarUxRestrictions.isRequiresDistractionOptimization
            }
        } catch (error: Exception) {
            Log.w(TAG, "ux restrictions unavailable: ${error.javaClass.simpleName}")
            mutableUxAllowed.value = false
        }
        try {
            if (context.checkSelfPermission(GEAR_PERMISSION) != PackageManager.PERMISSION_GRANTED) return
            val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
            if (manager.getCarPropertyConfig(VehiclePropertyIds.GEAR_SELECTION) == null) return
            propertyManager = manager
            mutableGear.value = fromAaos(manager.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0))
            manager.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        } catch (error: Exception) {
            Log.w(TAG, "gear unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun detach() {
        runCatching { uxManager?.unregisterListener() }
        runCatching { propertyManager?.unregisterCallback(gearCallback) }
        uxManager = null
        propertyManager = null
        mutableUxAllowed.value = false
        mutableGear.value = CarGear.UNKNOWN
    }

    private companion object {
        const val TAG = "DriveLogVehicle"
        const val GEAR_PERMISSION = "android.car.permission.CAR_POWERTRAIN"

        // android.car.VehicleGear values; everything forward counts as moving.
        fun fromAaos(value: Int) = when (value) {
            0 -> CarGear.UNKNOWN
            1 -> CarGear.NEUTRAL
            2 -> CarGear.REVERSE
            4 -> CarGear.PARK
            else -> CarGear.DRIVE
        }
    }
}
