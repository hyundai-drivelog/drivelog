package com.drivelog.server.domain.wash;

import static org.assertj.core.api.Assertions.assertThat;

import com.drivelog.server.domain.wash.WashForecast.MidDay;
import com.drivelog.server.domain.wash.WashForecast.RecommendationStatus;
import com.drivelog.server.domain.wash.WashForecast.ShortSlot;
import com.drivelog.server.domain.wash.WashForecast.WeatherType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WashRecommendationServiceTest {
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-09T09:00:00+09:00[Asia/Seoul]");

    private List<ShortSlot> safe48Hours() {
        List<ShortSlot> slots = new ArrayList<>();
        for (int hour = 1; hour <= 48; hour++)
            slots.add(new ShortSlot(NOW.toLocalDateTime().plusHours(hour), 10, 0, 1, "강수없음"));
        return slots;
    }

    private WashForecastCache.Snapshot snapshot(List<ShortSlot> shortSlots, List<MidDay> midDays) {
        return new WashForecastCache.Snapshot(shortSlots, midDays,
                NOW.toLocalDateTime().minusHours(4), NOW.toLocalDateTime().minusHours(3),
                Instant.parse("2026-11-09T00:00:00Z"), false, false, true);
    }

    @Test void recommendsOneSunnyDayAwayFromRain() {
        var slots = safe48Hours();
        slots.set(4, new ShortSlot(slots.get(4).time(), 20, 1, 4, "1mm 미만"));
        var response = WashRecommendationService.build(WashRegion.SEOUL, NOW, snapshot(slots, List.of()));
        assertThat(response.recommendationStatus()).isEqualTo(RecommendationStatus.RECOMMENDED);
        assertThat(response.headline()).contains("화요일");
        assertThat(response.dailyForecasts()).filteredOn(WashForecast.Day::highlighted).hasSize(1);
        assertThat(response.dailyForecasts().get(1).highlighted()).isTrue();
        slots.set(4, new ShortSlot(slots.get(4).time(), 70, 0, 4, "강수없음"));
        assertThat(WashRecommendationService.build(WashRegion.SEOUL, NOW, snapshot(slots, List.of()))
                .dailyForecasts().stream().filter(WashForecast.Day::highlighted).count()).isEqualTo(1);
    }

    @Test void incompleteSunnyDayIsNotRecommended() {
        var response = WashRecommendationService.build(WashRegion.SEOUL, NOW,
                snapshot(safe48Hours().subList(0, 8), List.of()));
        assertThat(response.recommendationStatus()).isEqualTo(RecommendationStatus.UNKNOWN);
        assertThat(response.dailyForecasts()).noneMatch(WashForecast.Day::highlighted);
    }

    @Test void shortForecastWinsOverlapAndPastDaysStayUnknownAtWeekBoundary() {
        ZonedDateTime thursday = NOW.plusDays(3);
        LocalDateTime friday = thursday.toLocalDateTime().plusDays(1);
        var shortSlots = List.of(new ShortSlot(friday, 20, 0, 1, "강수없음"));
        var midDays = List.of(new MidDay(friday.toLocalDate(), 80, 70, "비", "비"));
        var response = WashRecommendationService.build(WashRegion.SEOUL, thursday, snapshot(shortSlots, midDays));
        assertThat(response.dailyForecasts()).hasSize(7);
        assertThat(response.dailyForecasts().getFirst().weatherType()).isEqualTo(WeatherType.UNKNOWN);
        assertThat(response.dailyForecasts().getFirst().precipitationProbability()).isNull();
        assertThat(response.dailyForecasts().get(4).precipitationProbability()).isEqualTo(20);
    }

    @Test void unavailableForecastDoesNotBecomeDemoWeather() {
        var empty = new WashForecastCache.Snapshot(List.of(), List.of(), null, null,
                null, false, true, true);
        var response = WashRecommendationService.build(WashRegion.SEOUL, NOW, empty);
        assertThat(response.recommendationStatus()).isEqualTo(RecommendationStatus.UNKNOWN);
        assertThat(response.source()).isEqualTo("UNAVAILABLE");
        assertThat(response.availability()).isEqualTo(WashForecast.Availability.ERROR);
        assertThat(response.dailyForecasts()).allMatch(day -> day.precipitationProbability() == null);
    }
}
