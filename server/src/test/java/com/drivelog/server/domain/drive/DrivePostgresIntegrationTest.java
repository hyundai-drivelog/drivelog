package com.drivelog.server.domain.drive;

import com.drivelog.server.domain.drive.dto.DriveCreateRequest;
import com.drivelog.server.domain.drive.entity.DriveSourceType;
import com.drivelog.server.domain.drive.service.DriveService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres")
@ActiveProfiles("local")
@SpringBootTest
@Transactional
class DrivePostgresIntegrationTest {
    @Autowired private DriveService service;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void storesExactDecimalsInPostgresAndReadsAfterJpaContextClear() {
        var request = new DriveCreateRequest(OffsetDateTime.parse("2026-01-01T09:00:00+09:00"),
                OffsetDateTime.parse("2026-01-01T09:15:00+09:00"), 900L,
                new BigDecimal("12.345"), new BigDecimal("4.567"), "km/kWh", DriveSourceType.MOCK);
        var saved = service.create(request);
        entityManager.flush();
        entityManager.clear();
        var detail = service.get(saved.id());
        assertThat(detail.distanceKm()).isEqualByComparingTo("12.345");
        assertThat(detail.averageEfficiency()).isEqualByComparingTo("4.567");
        assertThat(detail.sourceType()).isEqualTo(DriveSourceType.MOCK);
        assertThat(detail.startedAt().toInstant()).isEqualTo(request.startedAt().toInstant());
        assertThat(detail.createdAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT distance_km FROM drivelog.drives WHERE id = ?", BigDecimal.class, saved.id()))
                .isEqualByComparingTo("12.345");
    }

    @Test
    void preservesUnknownEfficiencyAndReturnsNewestFirst() {
        var start = OffsetDateTime.parse("2030-01-01T00:00:00Z");
        var first = service.create(new DriveCreateRequest(start, start.plusSeconds(30), 30L,
                new BigDecimal("0.100"), null, null, DriveSourceType.MOCK));
        var second = service.create(new DriveCreateRequest(start.plusMinutes(1), start.plusMinutes(2), 60L,
                BigDecimal.ZERO, null, null, DriveSourceType.PLEOS));
        entityManager.flush();
        entityManager.clear();
        assertThat(service.get(first.id()).averageEfficiency()).isNull();
        assertThat(service.get(first.id()).efficiencyUnit()).isNull();
        var recent = service.recent(100);
        int firstIndex = java.util.stream.IntStream.range(0, recent.size()).filter(i -> recent.get(i).id().equals(first.id())).findFirst().orElseThrow();
        int secondIndex = java.util.stream.IntStream.range(0, recent.size()).filter(i -> recent.get(i).id().equals(second.id())).findFirst().orElseThrow();
        assertThat(secondIndex).isLessThan(firstIndex);
    }
}
