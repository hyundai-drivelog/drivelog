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
    private var energyManager: CarPropertyManager? = null
    private var connectedCar: Car? = null

    /** A runtime permission on AAOS. Asked for with location when a drive starts; a refusal only loses efficiency. */
    fun missingEnergyPermission(): String? = ENERGY_PERMISSION.takeIf {
        automotive && context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * The energy left on board, read once: the battery for an EV, otherwise the fuel. Null when the vehicle does
     * not report it, the permission is missing, or the value is not available right now.
     */
    fun energyLevel(): EnergyLevel? {
        if (context.checkSelfPermission(ENERGY_PERMISSION) != PackageManager.PERMISSION_GRANTED) return null
        // The permission is asked for at runtime, so it may have been granted after the car service connected.
        val manager = energyManager ?: (connectedCar?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)?.also { energyManager = it }
            ?: return null
        return readEnergy(manager, VehiclePropertyIds.EV_BATTERY_LEVEL, EnergyKind.BATTERY)
            ?: readEnergy(manager, VehiclePropertyIds.FUEL_LEVEL, EnergyKind.FUEL)
    }

    private fun readEnergy(manager: CarPropertyManager, propertyId: Int, kind: EnergyKind): EnergyLevel? = try {
        if (manager.getCarPropertyConfig(propertyId) == null) null
        else manager.getFloatProperty(propertyId, 0).toDouble().takeIf { it.isFinite() && it > 0.0 }?.let { EnergyLevel(it, kind) }
    } catch (error: Exception) {
        Log.w(TAG, "energy read failed: ${error.javaClass.simpleName}")
        null
    }

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
        connectedCar = connected
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
            if (context.checkSelfPermission(ENERGY_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                energyManager = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            }
        } catch (error: Exception) {
            Log.w(TAG, "energy unavailable: ${error.javaClass.simpleName}")
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
        energyManager = null
        connectedCar = null
        mutableUxAllowed.value = false
        mutableGear.value = CarGear.UNKNOWN
    }

    private companion object {
        const val TAG = "DriveLogVehicle"
        const val GEAR_PERMISSION = "android.car.permission.CAR_POWERTRAIN"
        const val ENERGY_PERMISSION = "android.car.permission.CAR_ENERGY"

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
