package com.drivelog.server.domain.wash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.drivelog.server.domain.wash.WashForecast.MidDay;
import com.drivelog.server.domain.wash.WashForecast.ShortSlot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WashForecastCacheTest {
    private final KmaForecastClient client = mock(KmaForecastClient.class);
    private final MutableClock clock = new MutableClock("2026-11-09T00:30:00Z");

    private void feed() {
        when(client.configured()).thenReturn(true);
        when(client.shortTerm(any(), any())).thenReturn(List.of(
                new ShortSlot(LocalDateTime.of(2026, 11, 9, 10, 0), 10, 0, 1, "강수없음")));
        when(client.midTerm(any(), any())).thenReturn(List.of(
                new MidDay(LocalDate.of(2026, 11, 13), 20, 30, "맑음", "구름많음")));
    }

    @Test void firstRequestFetchesSecondReusesAndNewIssueRefreshes() {
        feed();
        var cache = new WashForecastCache(client, clock, 12);
        assertThat(cache.get(WashRegion.SEOUL).shortSlots()).hasSize(1);
        cache.get(WashRegion.SEOUL);
        verify(client, times(1)).shortTerm(any(), any());
        verify(client, times(1)).midTerm(any(), any());
        clock.set("2026-11-09T02:16:00Z"); // 11:16 KST, 11:00 issue is available.
        cache.refreshHourly();
        verify(client, times(2)).shortTerm(any(), any());
        verify(client, times(1)).midTerm(any(), any());
    }

    @Test void failurePreservesLastGoodCacheAndRestartRefetches() {
        feed();
        var cache = new WashForecastCache(client, clock, 12);
        cache.get(WashRegion.SEOUL);
        when(client.shortTerm(any(), any())).thenThrow(new KmaException(KmaException.Kind.HTTP, "failure"));
        clock.set("2026-11-09T02:16:00Z");
        var stale = cache.get(WashRegion.SEOUL);
        assertThat(stale.shortSlots()).hasSize(1);
        assertThat(stale.stale()).isTrue();
        clock.set("2026-11-10T01:00:00Z");
        assertThat(cache.get(WashRegion.SEOUL).shortSlots()).isEmpty();
        reset(client);
        feed();
        var restarted = new WashForecastCache(client, clock, 12);
        restarted.get(WashRegion.SEOUL);
        verify(client, atLeastOnce()).shortTerm(any(), any());
    }

    @Test void concurrentRequestsShareOneFetch() throws Exception {
        feed();
        var cache = new WashForecastCache(client, clock, 12);
        var pool = Executors.newFixedThreadPool(10);
        try {
            var gate = new CountDownLatch(1);
            var tasks = java.util.stream.IntStream.range(0, 10).mapToObj(index -> pool.submit(() -> {
                gate.await();
                return cache.get(WashRegion.SEOUL);
            })).toList();
            gate.countDown();
            for (var task : tasks) assertThat(task.get(3, TimeUnit.SECONDS).shortSlots()).hasSize(1);
            verify(client, times(1)).shortTerm(any(), any());
            verify(client, times(1)).midTerm(any(), any());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void issueCalculationRespectsKstMidnightAndPublicationLag() {
        assertThat(WashForecastCache.latestIssue(
                java.time.ZonedDateTime.parse("2026-11-09T02:10:00+09:00[Asia/Seoul]"),
                new int[]{2, 5, 8, 11, 14, 17, 20, 23}, 15))
                .isEqualTo(LocalDateTime.of(2026, 11, 8, 23, 0));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(String initial) { instant = new AtomicReference<>(Instant.parse(initial)); }
        void set(String value) { instant.set(Instant.parse(value)); }
        @Override public ZoneId getZone() { return WashForecastCache.KST; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
