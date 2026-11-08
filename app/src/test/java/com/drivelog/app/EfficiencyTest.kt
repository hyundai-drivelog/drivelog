package com.drivelog.app

import com.drivelog.app.core.journey.DistanceSource
import com.drivelog.app.core.journey.RecordStatus
import com.drivelog.app.core.journey.RecordV
import com.drivelog.app.core.vehicle.EnergyKind
import com.drivelog.app.core.vehicle.EnergyLevel
import com.drivelog.app.core.vehicle.EnergyTrack
import com.drivelog.app.core.vehicle.resolveEfficiency
import com.drivelog.app.feature.car.efficiencyCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EfficiencyTest {
    private fun battery(wh: Double) = EnergyLevel(wh, EnergyKind.BATTERY)
    private fun fuel(ml: Double) = EnergyLevel(ml, EnergyKind.FUEL)

    @Test fun batteryDropGivesKmPerKwh() {
        val result = resolveEfficiency(3.4, battery(77_400.0), battery(76_900.0), 1.0)!!
        assertEquals(6.8, result.value, 1e-9)
        assertEquals("km/kWh", result.unit)
        assertEquals(0.5, result.used, 1e-9)
    }

    @Test fun fuelDropGivesKmPerLitre() {
        val result = resolveEfficiency(14.2, fuel(40_000.0), fuel(39_000.0), 1.0)!!
        assertEquals(14.2, result.value, 1e-9)
        assertEquals("km/L", result.unit)
    }

    @Test fun nothingWhenAReadingIsMissingOrKindsDiffer() {
        assertNull(resolveEfficiency(5.0, null, battery(70_000.0), 1.0))
        assertNull(resolveEfficiency(5.0, battery(71_000.0), null, 1.0))
        assertNull(resolveEfficiency(5.0, battery(71_000.0), fuel(70_000.0), 1.0))
    }

    @Test fun nothingWhenTheReadingDidNotFallOrTheCarCharged() {
        assertNull(resolveEfficiency(5.0, battery(70_000.0), battery(70_000.0), 1.0))
        assertNull(resolveEfficiency(5.0, battery(70_000.0), battery(72_000.0), 1.0))
    }

    @Test fun nothingForShortDrivesOrUnknownDistance() {
        assertNull(resolveEfficiency(0.9, battery(70_000.0), battery(69_800.0), 1.0))
        assertNull(resolveEfficiency(null, battery(70_000.0), battery(69_000.0), 1.0))
    }

    @Test fun nothingWhenTheResultIsNotARoadCar() {
        // 10 km on 10 Wh would be 1000 km/kWh: the readings are not what they claim.
        assertNull(resolveEfficiency(10.0, battery(70_000.0), battery(69_990.0), 1.0))
        assertNull(resolveEfficiency(1.0, battery(70_000.0), battery(60_000.0), 1.0))
    }

    @Test fun nothingWhenTheDropIsOnlyAFewOfTheReadingsSteps() {
        // The emulator reports whole kWh: 77.4 to 77.0 is one step, not a measured 0.4 kWh.
        assertNull(resolveEfficiency(5.5, battery(77_400.0), battery(77_000.0), 400.0))
        assertNull(resolveEfficiency(30.0, battery(77_000.0), battery(73_000.0), 1000.0))
        assertEquals(6.0, resolveEfficiency(30.0, battery(77_000.0), battery(72_000.0), 1000.0)!!.value, 1e-9)
        assertNull(resolveEfficiency(5.0, battery(70_000.0), battery(69_000.0), null))
    }

    @Test fun trackLearnsTheSmallestStep() {
        val track = EnergyTrack(battery(77_400.0))
        assertNull(track.step)
        track.sample(battery(77_400.0))
        track.sample(battery(77_000.0))
        track.sample(battery(76_000.0))
        track.sample(fuel(10.0))
        track.sample(null)
        assertEquals(400.0, track.step!!, 1e-9)
        track.sample(battery(76_010.0))
        assertEquals(10.0, track.step!!, 1e-9)
    }

    private fun record(efficiency: Double?, unit: String?, used: Double?, simulated: Boolean = false) = RecordV(
        "r", null, null, "송정", 0, 0, 0, 3.4, DistanceSource.GPS, RecordStatus.COMPLETE, simulated, emptyList(),
        efficiency = efficiency, efficiencyUnit = unit, energyUsed = used,
    )

    @Test fun cellReadsValueOverUsage() {
        assertEquals("6.8 km/kWh" to "전비 · 0.50 kWh 사용", efficiencyCell(record(6.8, "km/kWh", 0.5)))
        assertEquals("14.2 km/L" to "연비 · 1.00 L 사용", efficiencyCell(record(14.2, "km/L", 1.0)))
    }

    @Test fun noCellWithoutAMeasurementOrForASimulatedDrive() {
        assertNull(efficiencyCell(record(null, null, null)))
        assertNull(efficiencyCell(record(6.8, "km/kWh", 0.5, simulated = true)))
    }
}
