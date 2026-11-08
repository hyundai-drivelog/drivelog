package com.drivelog.app.core.vehicle

/** What the vehicle stores its energy in, as the standard AAOS properties report it. */
enum class EnergyKind(val efficiencyUnit: String, val usedUnit: String) {
    /** EV_BATTERY_LEVEL, in watt-hours. */
    BATTERY("km/kWh", "kWh"),
    /** FUEL_LEVEL, in millilitres. */
    FUEL("km/L", "L"),
}

/** One reading of the energy left on board, in Wh for a battery and mL for fuel. */
data class EnergyLevel(val amount: Double, val kind: EnergyKind)

/** Distance per unit of energy over one drive, and the energy it took (kWh or L). */
data class DriveEfficiency(val value: Double, val unit: String, val used: Double)

/** Drives shorter than this move the reading too little to say anything. */
const val MIN_EFFICIENCY_KM = 1.0

/**
 * The energy used must span at least this many of the reading's smallest steps. A level reported in whole kWh
 * that falls by one step could have fallen by almost nothing or by almost two; five keeps the error near 20%.
 */
const val MIN_EFFICIENCY_STEPS = 5

/**
 * Samples the energy level through a drive to learn how finely the vehicle reports it: the smallest change seen
 * between two readings. Vehicles differ, and the emulator's level moves in whole kWh.
 */
class EnergyTrack(val start: EnergyLevel) {
    private var last = start
    /** Smallest change seen between readings, in the reading's own unit (Wh or mL). Null until it moves. */
    var step: Double? = null
        private set

    fun sample(level: EnergyLevel?) {
        if (level == null || level.kind != start.kind) return
        val change = kotlin.math.abs(level.amount - last.amount)
        if (change > 0.0) step = step?.let { minOf(it, change) } ?: change
        last = level
    }
}

/**
 * Efficiency from the energy left at the start and at the end of the drive. Null when either reading is missing,
 * the kinds differ, nothing measurable was used (charging on the way, or a drop of only a few of the reading's
 * steps, [step], so the figure would mostly be rounding), or the result is outside what a road car does, which
 * means the readings are not what they claim to be.
 */
fun resolveEfficiency(distanceKm: Double?, start: EnergyLevel?, end: EnergyLevel?, step: Double?): DriveEfficiency? {
    if (distanceKm == null || distanceKm < MIN_EFFICIENCY_KM) return null
    if (start == null || end == null || start.kind != end.kind) return null
    val drop = start.amount - end.amount
    if (!drop.isFinite() || drop <= 0.0) return null
    if (step == null || drop < step * MIN_EFFICIENCY_STEPS) return null
    val used = drop / 1000.0
    val value = distanceKm / used
    val plausible = when (start.kind) {
        EnergyKind.BATTERY -> 1.0..15.0
        EnergyKind.FUEL -> 2.0..50.0
    }
    if (value !in plausible) return null
    return DriveEfficiency(value, start.kind.efficiencyUnit, used)
}
