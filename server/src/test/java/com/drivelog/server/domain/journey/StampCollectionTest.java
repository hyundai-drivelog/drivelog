package com.drivelog.server.domain.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StampCollectionTest {
    private final StampCollection collection = new StampCollection(new ObjectMapper());

    private JourneyDtos.RecordView record(String id, long start, Double km, double lat, double lon) {
        return new JourneyDtos.RecordView(id, null, null, "도착", start, start + 60000, 60,
                km, "GPS_ESTIMATED", "COMPLETE", false,
                List.of(List.of(37.5, 126.9, (double) start), List.of(lat, lon, (double) start + 60000)),
                null, null, false);
    }

    @Test void earliestVisitWinsAndDeletingItRevealsNextVisit() {
        var first = record("first", 1000, 0.1, 37.5786111, 126.9772222);
        var later = record("later", 2000, 1.0, 37.5786111, 126.9772222);
        var visits = collection.collect(List.of(later, first));
        assertThat(visits).hasSize(1);
        assertThat(visits.getFirst().recordId()).isEqualTo("first");
        assertThat(collection.collect(List.of(later)).getFirst().recordId()).isEqualTo("later");
    }

    @Test void excludesShortUnknownDistanceAndRemoteArrivals() {
        assertThat(collection.collect(List.of(
                record("short", 1000, 0.099, 37.5786111, 126.9772222),
                record("unknown", 1000, null, 37.5786111, 126.9772222),
                record("remote", 1000, 10.0, 0, 0)))).isEmpty();
    }
}
