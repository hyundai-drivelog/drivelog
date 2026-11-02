package com.drivelog.server.domain.drive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

@Entity
@Table(name = "drives", schema = "drivelog")
public class Drive {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private OffsetDateTime startedAt;
    @Column(nullable = false, updatable = false)
    private OffsetDateTime endedAt;
    @Column(nullable = false, updatable = false)
    private Long durationSeconds;
    @Column(nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal distanceKm;
    @Column(updatable = false, precision = 12, scale = 3)
    private BigDecimal averageEfficiency;
    @Column(updatable = false, length = 16)
    private String efficiencyUnit;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private DriveSourceType sourceType;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Drive() {}

    public Drive(OffsetDateTime startedAt, OffsetDateTime endedAt, Long durationSeconds,
                 BigDecimal distanceKm, BigDecimal averageEfficiency, String efficiencyUnit,
                 DriveSourceType sourceType) {
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.durationSeconds = durationSeconds;
        this.distanceKm = distanceKm;
        this.averageEfficiency = averageEfficiency;
        this.efficiencyUnit = efficiencyUnit;
        this.sourceType = sourceType;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getEndedAt() { return endedAt; }
    public Long getDurationSeconds() { return durationSeconds; }
    public BigDecimal getDistanceKm() { return distanceKm; }
    public BigDecimal getAverageEfficiency() { return averageEfficiency; }
    public String getEfficiencyUnit() { return efficiencyUnit; }
    public DriveSourceType getSourceType() { return sourceType; }
    public Instant getCreatedAt() { return createdAt; }
}
