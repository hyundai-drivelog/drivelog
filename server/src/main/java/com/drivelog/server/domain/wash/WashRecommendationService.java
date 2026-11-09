package com.drivelog.server.domain.wash;

import com.drivelog.server.domain.wash.WashForecast.Availability;
import com.drivelog.server.domain.wash.WashForecast.Day;
import com.drivelog.server.domain.wash.WashForecast.MidDay;
import com.drivelog.server.domain.wash.WashForecast.RecommendationStatus;
import com.drivelog.server.domain.wash.WashForecast.ShortSlot;
import com.drivelog.server.domain.wash.WashForecast.WeatherType;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class WashRecommendationService {
    static final int HIGH_RISK_POP = 60;
    static final int LOW_RISK_POP = 30;
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("M월 d일 HH시", Locale.KOREAN);
    private final WashForecastCache cache;
    private final Clock clock;

    WashRecommendationService(WashForecastCache cache, Clock clock) {
        this.cache = cache;
        this.clock = clock;
    }

    WashRecommendationResponse recommendation(WashRegion region) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(WashForecastCache.KST);
        return build(region, now, cache.get(region));
    }

    static WashRecommendationResponse build(WashRegion region, ZonedDateTime now, WashForecastCache.Snapshot snapshot) {
        LocalDateTime localNow = now.withZoneSameInstant(WashForecastCache.KST).toLocalDateTime();
        LocalDate monday = localNow.toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        Map<LocalDate, List<ShortSlot>> shortDays = new HashMap<>();
        snapshot.shortSlots().stream().filter(slot -> slot.time().isAfter(localNow)).forEach(slot ->
                shortDays.computeIfAbsent(slot.time().toLocalDate(), ignored -> new ArrayList<>()).add(slot));
        Map<LocalDate, MidDay> midDays = new HashMap<>();
        snapshot.midDays().forEach(day -> midDays.put(day.date(), day));
        List<Day> days = new ArrayList<>(7);
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = monday.plusDays(offset);
            if (date.isBefore(localNow.toLocalDate())) {
                days.add(new Day(date, WeatherType.UNKNOWN, null, false));
            } else if (shortDays.containsKey(date)) {
                days.add(shortDay(date, shortDays.get(date)));
            } else if (midDays.containsKey(date)) {
                MidDay mid = midDays.get(date);
                Integer probability = mid.maxPop();
                WeatherType weather = mid.weather();
                days.add(new Day(date, weather, probability, false));
            } else {
                days.add(new Day(date, WeatherType.UNKNOWN, null, false));
            }
        }

        Decision decision = decide(days, shortDays, midDays, snapshot.shortSlots(), localNow);
        boolean anyForecast = days.stream().anyMatch(day -> day.weatherType() != WeatherType.UNKNOWN
                || day.precipitationProbability() != null);
        Availability availability = anyForecast ? Availability.AVAILABLE
                : snapshot.failed() ? Availability.ERROR : Availability.NO_DATA;
        if (availability != Availability.AVAILABLE) decision = Decision.unknown();
        if (decision.status() == RecommendationStatus.RECOMMENDED) {
            LocalDate recommendedDate = decision.recommendedDate();
            days.replaceAll(day -> new Day(day.date(), day.weatherType(),
                    day.precipitationProbability(), day.date().equals(recommendedDate)));
        }
        String headline;
        String summary;
        String homeSummary;
        String reason;
        if (availability == Availability.ERROR) {
            headline = "날씨 정보를 불러오지 못했어요";
            summary = "잠시 후 다시 확인해 주세요.";
            homeSummary = "아직 날씨 정보를 확인할 수 없어요";
            reason = "예보를 받아오지 못해 판단할 수 없어요.";
        } else if (availability == Availability.NO_DATA) {
            headline = "이번 주 날씨 정보가 없어요";
            summary = "예보가 준비되면 세차 추천을 확인할 수 있어요.";
            homeSummary = "아직 날씨 정보를 확인할 수 없어요";
            reason = "예보 자료가 없어 세차 시점을 판단하기 어려워요.";
        } else if (decision.status() == RecommendationStatus.POSTPONE) {
            boolean nextWeek = decision.riskTime().toLocalDate().isAfter(monday.plusDays(6));
            headline = nextWeek ? "지금은\n세차를 미루세요" : "이번 주는\n세차를 미루세요";
            summary = nextWeek ? "다가오는 48시간 안에 강수 위험이 있어요." : "가까운 날짜에 강수 위험이 있어요.";
            String weekday = decision.riskTime().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN);
            homeSummary = weekday + " 강수 위험 · 세차 미루기";
            reason = decision.reason();
        } else if (decision.status() == RecommendationStatus.RECOMMENDED) {
            String weekday = decision.recommendedDate().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN);
            headline = weekday + "에\n세차를 추천해요";
            summary = weekday + "은 맑고 강수확률이 낮아요.";
            homeSummary = weekday + " 세차 추천";
            reason = decision.reason();
        } else {
            headline = "세차 시점을\n판단하기 어려워요";
            summary = "가까운 기간의 예보 자료가 충분하지 않아요.";
            homeSummary = "아직 날씨 정보를 확인할 수 없어요";
            reason = "예보 자료가 부족하거나 불확실해 세차 시점을 판단하기 어려워요.";
        }
        LocalDateTime issued = snapshot.shortIssue();
        if (snapshot.midIssue() != null && (issued == null || snapshot.midIssue().isAfter(issued))) issued = snapshot.midIssue();
        return new WashRecommendationResponse(region.name(), region.displayName, decision.status(),
                headline, summary, homeSummary, reason, List.copyOf(days),
                anyForecast ? "KMA" : "UNAVAILABLE", issued == null ? null : issued.atZone(WashForecastCache.KST).toOffsetDateTime().toString(),
                snapshot.updatedAt() == null ? null : snapshot.updatedAt().atZone(WashForecastCache.KST).toOffsetDateTime().toString(),
                snapshot.stale(), availability);
    }

    private static Day shortDay(LocalDate date, List<ShortSlot> slots) {
        Integer maxPop = slots.stream().map(ShortSlot::pop).filter(value -> value != null)
                .max(Integer::compareTo).orElse(null);
        boolean precipitation = slots.stream().anyMatch(ShortSlot::precipitation);
        boolean risk = precipitation || maxPop != null && maxPop >= HIGH_RISK_POP;
        WeatherType weather = risk ? WeatherType.RAINY
                : slots.stream().anyMatch(slot -> slot.sky() != null && slot.sky() >= 3) ? WeatherType.CLOUDY
                : slots.stream().anyMatch(slot -> slot.sky() != null && slot.sky() == 1) ? WeatherType.SUNNY
                : WeatherType.UNKNOWN;
        return new Day(date, weather, maxPop, false);
    }

    static Decision decide(List<Day> days, Map<LocalDate, List<ShortSlot>> shortDays,
                           Map<LocalDate, MidDay> midDays, List<ShortSlot> slots, LocalDateTime now) {
        // Only a complete, dry and sunny day can be recommended. Short-term data wins on overlap.
        List<LocalDate> rainyDates = new ArrayList<>();
        shortDays.forEach((date, values) -> {
            if (shortDay(date, values).weatherType() == WeatherType.RAINY) rainyDates.add(date);
        });
        midDays.forEach((date, value) -> {
            if (!shortDays.containsKey(date) && (value.weather() == WeatherType.RAINY
                    || value.maxPop() != null && value.maxPop() >= HIGH_RISK_POP)) rainyDates.add(date);
        });
        Day best = null;
        long bestDistance = -1;
        for (Day day : days) {
            if (day.date().isBefore(now.toLocalDate()) || day.weatherType() != WeatherType.SUNNY
                    || day.precipitationProbability() == null || day.precipitationProbability() > LOW_RISK_POP) continue;
            List<ShortSlot> shortSlots = shortDays.get(day.date());
            if (shortSlots != null && !completeSunnyShortDay(shortSlots, now, day.date())) continue;
            MidDay mid = midDays.get(day.date());
            if (shortSlots == null && (mid == null || mid.amPop() == null || mid.pmPop() == null
                    || !"맑음".equals(mid.amWeather()) || !"맑음".equals(mid.pmWeather()))) continue;
            long distance = rainyDates.stream().mapToLong(rain -> Math.abs(ChronoUnit.DAYS.between(day.date(), rain)))
                    .min().orElse(Long.MAX_VALUE);
            if (best == null || distance > bestDistance
                    || distance == bestDistance && day.precipitationProbability() < best.precipitationProbability()
                    || distance == bestDistance && day.precipitationProbability().equals(best.precipitationProbability())
                    && day.date().isBefore(best.date())) {
                best = day;
                bestDistance = distance;
            }
        }
        if (best != null) {
            String weekday = best.date().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN);
            String reason = rainyDates.isEmpty()
                    ? weekday + "은 맑고 강수확률이 낮아요."
                    : weekday + "은 강수 위험이 높은 날과 가장 멀고 강수확률이 낮아요.";
            return new Decision(RecommendationStatus.RECOMMENDED, null, best.date(), reason);
        }
        LocalDateTime end = now.plusHours(48);
        for (ShortSlot slot : slots.stream().filter(slot -> slot.time().isAfter(now) && !slot.time().isAfter(end))
                .sorted(Comparator.comparing(ShortSlot::time)).toList()) {
            if (slot.pty() != null && slot.pty() > 0) {
                return new Decision(RecommendationStatus.POSTPONE, slot.time(), null,
                        slot.time().format(DISPLAY_TIME) + "에 비나 눈이 예보되어 있어요.");
            }
            if (slot.pop() != null && slot.pop() >= HIGH_RISK_POP) {
                return new Decision(RecommendationStatus.POSTPONE, slot.time(), null,
                        slot.time().format(DISPLAY_TIME) + " 강수확률이 " + slot.pop() + "%로 높아요.");
            }
        }
        return Decision.unknown();
    }

    private static boolean completeSunnyShortDay(List<ShortSlot> slots, LocalDateTime now, LocalDate date) {
        List<ShortSlot> ordered = slots.stream().sorted(Comparator.comparing(ShortSlot::time)).toList();
        if (ordered.size() < 6 || ordered.getFirst().time().isAfter(now.toLocalDate().equals(date)
                ? now.plusHours(3) : date.atTime(9, 0))
                || ordered.getLast().time().isBefore(date.atTime(18, 0))) return false;
        ShortSlot previous = null;
        for (ShortSlot slot : ordered) {
            if (!slot.complete() || slot.sky() == null || slot.sky() != 1
                    || slot.pop() > LOW_RISK_POP || slot.precipitation()) return false;
            if (previous != null && Duration.between(previous.time(), slot.time()).toHours() > 3) return false;
            previous = slot;
        }
        return true;
    }

    record Decision(RecommendationStatus status, LocalDateTime riskTime, LocalDate recommendedDate, String reason) {
        static Decision unknown() { return new Decision(RecommendationStatus.UNKNOWN, null, null, null); }
    }
}
