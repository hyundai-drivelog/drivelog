package com.drivelog.server.domain.drive.dto;

import com.drivelog.server.domain.drive.entity.DriveSourceType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;

public record DriveCreateRequest(
        @NotNull OffsetDateTime startedAt,
        @NotNull OffsetDateTime endedAt,
        @NotNull @PositiveOrZero Long durationSeconds,
        @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal distanceKm,
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal averageEfficiency,
        @Pattern(regexp = "km/L|km/kWh") String efficiencyUnit,
        @NotNull DriveSourceType sourceType
) {
    @JsonIgnore
    @AssertTrue(message = "endedAt must be at or after startedAt")
    public boolean isTimeOrderValid() {
        return startedAt == null || endedAt == null || !endedAt.isBefore(startedAt);
    }

    @JsonIgnore
    @AssertTrue(message = "durationSeconds must match the elapsed whole seconds")
    public boolean isDurationConsistent() {
        return startedAt == null || endedAt == null || durationSeconds == null
                || durationSeconds == Duration.between(startedAt, endedAt).getSeconds();
    }

    @JsonIgnore
    @AssertTrue(message = "averageEfficiency and efficiencyUnit must both be present or both be null")
    public boolean isEfficiencyPairValid() {
        return (averageEfficiency == null) == (efficiencyUnit == null);
    }

    @JsonIgnore
    @AssertTrue(message = "timestamps must use at most microsecond precision")
    public boolean isTimestampPrecisionValid() {
        return (startedAt == null || startedAt.getNano() % 1000 == 0)
                && (endedAt == null || endedAt.getNano() % 1000 == 0);
    }
}
