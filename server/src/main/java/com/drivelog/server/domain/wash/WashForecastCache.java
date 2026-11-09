package com.drivelog.server.domain.wash;

import com.drivelog.server.domain.wash.WashForecast.MidDay;
import com.drivelog.server.domain.wash.WashForecast.ShortSlot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Per-region memory cache. A region lock covers fetch and atomic replacement. */
@Component
class WashForecastCache {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int[] SHORT_HOURS = {2, 5, 8, 11, 14, 17, 20, 23};
    private static final int[] MID_HOURS = {6, 18};
    private final KmaForecastClient client;
    private final Clock clock;
    private final Duration staleLimit;
    private final Map<WashRegion, RegionEntry> entries = new ConcurrentHashMap<>();

    WashForecastCache(KmaForecastClient client, Clock clock,
                      @Value("${KMA_STALE_MAX_HOURS:12}") long staleHours) {
        this.client = client;
        this.clock = clock;
        this.staleLimit = Duration.ofHours(Math.max(1, staleHours));
    }

    Snapshot get(WashRegion region) {
        RegionEntry entry = entries.computeIfAbsent(region, ignored -> new RegionEntry());
        synchronized (entry) {
            refresh(region, entry, ZonedDateTime.now(clock).withZoneSameInstant(KST));
            return snapshot(entry, ZonedDateTime.now(clock).withZoneSameInstant(KST));
        }
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    void refreshHourly() {
        if (!client.configured()) return;
        for (WashRegion region : entries.keySet()) get(region);
    }

    private void refresh(WashRegion region, RegionEntry entry, ZonedDateTime now) {
        if (!client.configured()) return;
        LocalDateTime shortIssue = latestIssue(now, SHORT_HOURS, 15);
        LocalDateTime midIssue = latestIssue(now, MID_HOURS, 30);
        if (shouldTry(entry.shortIssue, entry.shortAttemptAt, entry.shortRetryDelay, shortIssue, now.toInstant())) {
            entry.shortAttemptIssue = shortIssue;
            entry.shortAttemptAt = now.toInstant();
            try {
                List<ShortSlot> fetched = client.shortTerm(region, shortIssue);
                LocalDateTime actualIssue = shortIssue;
                if (fetched.isEmpty() && entry.shortSlots.isEmpty()) {
                    actualIssue = latestIssue(shortIssue.atZone(KST), SHORT_HOURS, 1);
                    fetched = client.shortTerm(region, actualIssue);
                }
                if (!fetched.isEmpty()) {
                    entry.shortSlots = List.copyOf(fetched);
                    entry.shortIssue = actualIssue;
                    entry.shortUpdatedAt = now.toInstant();
                    entry.shortError = false;
                }
            } catch (KmaException ex) {
                entry.shortError = true;
                entry.shortRetryDelay = ex.kind == KmaException.Kind.AUTH || ex.kind == KmaException.Kind.RATE_LIMIT
                        ? Duration.ofHours(1) : Duration.ofMinutes(10);
            }
        }
        if (shouldTry(entry.midIssue, entry.midAttemptAt, entry.midRetryDelay, midIssue, now.toInstant())) {
            entry.midAttemptIssue = midIssue;
            entry.midAttemptAt = now.toInstant();
            try {
                List<MidDay> fetched = client.midTerm(region, midIssue);
                LocalDateTime actualIssue = midIssue;
                if (fetched.isEmpty() && entry.midDays.isEmpty()) {
                    actualIssue = latestIssue(midIssue.atZone(KST), MID_HOURS, 1);
                    fetched = client.midTerm(region, actualIssue);
                }
                if (!fetched.isEmpty()) {
                    entry.midDays = List.copyOf(fetched);
                    entry.midIssue = actualIssue;
                    entry.midUpdatedAt = now.toInstant();
                    entry.midError = false;
                }
            } catch (KmaException ex) {
                entry.midError = true;
                entry.midRetryDelay = ex.kind == KmaException.Kind.AUTH || ex.kind == KmaException.Kind.RATE_LIMIT
                        ? Duration.ofHours(1) : Duration.ofMinutes(10);
            }
        }
    }

    private boolean shouldTry(LocalDateTime cached, Instant attemptAt, Duration retryDelay,
                              LocalDateTime expected, Instant now) {
        if (cached != null && !cached.isBefore(expected)) return false;
        return attemptAt == null || !now.isBefore(attemptAt.plus(retryDelay));
    }

    private Snapshot snapshot(RegionEntry entry, ZonedDateTime now) {
        Instant instant = now.toInstant();
        boolean shortValid = entry.shortUpdatedAt != null
                && !instant.isAfter(entry.shortUpdatedAt.plus(staleLimit));
        boolean midValid = entry.midUpdatedAt != null
                && !instant.isAfter(entry.midUpdatedAt.plus(staleLimit));
        List<ShortSlot> shortSlots = shortValid ? entry.shortSlots : List.of();
        List<MidDay> midDays = midValid ? entry.midDays : List.of();
        boolean stale = (shortValid && entry.shortIssue.isBefore(latestIssue(now, SHORT_HOURS, 15)))
                || (midValid && entry.midIssue.isBefore(latestIssue(now, MID_HOURS, 30)));
        Instant updated = shortValid ? entry.shortUpdatedAt : null;
        if (midValid && (updated == null || entry.midUpdatedAt.isAfter(updated))) updated = entry.midUpdatedAt;
        return new Snapshot(shortSlots, midDays, shortValid ? entry.shortIssue : null,
                midValid ? entry.midIssue : null, updated, stale,
                entry.shortError || entry.midError, client.configured());
    }

    static LocalDateTime latestIssue(ZonedDateTime now, int[] hours, int lagMinutes) {
        LocalDateTime available = now.withZoneSameInstant(KST).toLocalDateTime().minusMinutes(lagMinutes);
        for (int day = 0; day <= 2; day++) {
            LocalDate date = available.toLocalDate().minusDays(day);
            for (int i = hours.length - 1; i >= 0; i--) {
                LocalDateTime issue = date.atTime(hours[i], 0);
                if (!issue.isAfter(available)) return issue;
            }
        }
        throw new IllegalStateException("No forecast issue found");
    }

    record Snapshot(List<ShortSlot> shortSlots, List<MidDay> midDays,
                    LocalDateTime shortIssue, LocalDateTime midIssue, Instant updatedAt,
                    boolean stale, boolean failed, boolean configured) {}

    private static final class RegionEntry {
        List<ShortSlot> shortSlots = List.of();
        List<MidDay> midDays = List.of();
        LocalDateTime shortIssue;
        LocalDateTime midIssue;
        Instant shortUpdatedAt;
        Instant midUpdatedAt;
        LocalDateTime shortAttemptIssue;
        LocalDateTime midAttemptIssue;
        Instant shortAttemptAt;
        Instant midAttemptAt;
        boolean shortError;
        boolean midError;
        Duration shortRetryDelay = Duration.ofMinutes(10);
        Duration midRetryDelay = Duration.ofMinutes(10);
    }
}
