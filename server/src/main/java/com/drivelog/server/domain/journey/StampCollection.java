package com.drivelog.server.domain.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import org.springframework.core.io.ClassPathResource;

/** Same arrival rule as the car; records remain the source of truth, including deletions. */
final class StampCollection {
    record Place(String id, String name, String region, String area, String motto,
                 double latitude, double longitude, String art, double tilt) {}
    record Visit(Place place, String recordId, long visitedAtMillis) {}
    private final List<Place> places;

    StampCollection(ObjectMapper json) {
        try (var input = new ClassPathResource("stamps/catalog.json").getInputStream()) {
            var result = new ArrayList<Place>();
            for (var region : json.readTree(input).path("regions")) {
                for (var p : region.path("places")) result.add(new Place(p.path("id").asText(),
                        p.path("name").asText(), region.path("name").asText(), p.path("area").asText(),
                        p.path("motto").asText(), p.path("latitude").asDouble(), p.path("longitude").asDouble(),
                        p.path("art").asText(), p.path("tilt").asDouble()));
            }
            places = List.copyOf(result);
        } catch (IOException error) { throw new IllegalStateException("Stamp catalog unavailable", error); }
    }

    List<Visit> collect(List<JourneyDtos.RecordView> records) {
        var visits = new LinkedHashMap<String, Visit>();
        records.stream().sorted(Comparator.comparingLong(JourneyDtos.RecordView::startedAtMillis)
                .thenComparing(JourneyDtos.RecordView::id)).forEach(record -> {
            if (record.simulated() || record.distanceKm() == null || record.distanceKm() < 0.1
                    || record.route().size() < 2) return;
            var end = record.route().getLast();
            if (end.size() < 2 || end.get(0) == null || end.get(1) == null
                    || !Double.isFinite(end.get(0)) || !Double.isFinite(end.get(1))
                    || Math.abs(end.get(0)) > 90 || Math.abs(end.get(1)) > 180) return;
            Place nearest = null;
            double best = Double.POSITIVE_INFINITY;
            for (var place : places) {
                double km = distance(end.get(0), end.get(1), place.latitude(), place.longitude());
                if (km < best) { best = km; nearest = place; }
            }
            if (nearest != null && best <= 1.0) visits.putIfAbsent(nearest.id(),
                    new Visit(nearest, record.id(), record.startedAtMillis()));
        });
        return List.copyOf(visits.values());
    }

    private static double distance(double lat, double lon, double otherLat, double otherLon) {
        double a = Math.pow(Math.sin(Math.toRadians(otherLat - lat) / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(otherLat))
                * Math.pow(Math.sin(Math.toRadians(otherLon - lon) / 2), 2);
        return 6371.0088 * 2 * Math.asin(Math.sqrt(Math.max(0.0, Math.min(1.0, a))));
    }
}
