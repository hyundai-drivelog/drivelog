package com.drivelog.app.core.drive

import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Explicit demo fixture. Never reported as measured vehicle data. */
class MockDriveDataSource : DriveDataSource {
    override suspend fun capture(): DrivePayload {
        val end = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        return DrivePayload(
            startedAt = end.minusSeconds(900).toString(),
            endedAt = end.toString(),
            durationSeconds = 900,
            distanceKm = BigDecimal("12.450"),
            averageEfficiency = BigDecimal("14.200"),
            efficiencyUnit = "km/L",
            sourceType = DriveSourceType.MOCK,
        )
    }
}
