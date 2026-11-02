package com.drivelog.server.domain.drive.dto;

import com.drivelog.server.domain.drive.entity.Drive;
import com.drivelog.server.domain.drive.entity.DriveSourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

public record DriveResponse(Long id, OffsetDateTime startedAt, OffsetDateTime endedAt,
                            Long durationSeconds, BigDecimal distanceKm, BigDecimal averageEfficiency,
                            String efficiencyUnit, DriveSourceType sourceType, Instant createdAt) {
    public static DriveResponse from(Drive drive) {
        return new DriveResponse(drive.getId(), drive.getStartedAt(), drive.getEndedAt(),
                drive.getDurationSeconds(), drive.getDistanceKm(), drive.getAverageEfficiency(),
                drive.getEfficiencyUnit(), drive.getSourceType(), drive.getCreatedAt());
    }
}
