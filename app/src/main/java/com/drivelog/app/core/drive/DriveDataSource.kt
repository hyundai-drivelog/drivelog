package com.drivelog.app.core.drive

import java.math.BigDecimal

enum class DriveSourceType { MOCK, PLEOS }

data class DrivePayload(
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Long,
    val distanceKm: BigDecimal,
    val averageEfficiency: BigDecimal?,
    val efficiencyUnit: String?,
    val sourceType: DriveSourceType,
)

interface DriveDataSource {
    suspend fun capture(): DrivePayload
}

class PleosUnavailableException(val explanation: String) : Exception(explanation)
