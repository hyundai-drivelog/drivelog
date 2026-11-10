package com.drivelog.server.domain.wash;

import java.util.List;

public record WashRecommendationResponse(
        String regionCode,
        String regionName,
        WashForecast.RecommendationStatus recommendationStatus,
        String headline,
        String summary,
        String homeSummary,
        String reason,
        List<WashForecast.Day> dailyForecasts,
        String source,
        String forecastIssuedAt,
        String lastUpdatedAt,
        boolean stale,
        WashForecast.Availability availability
) {}
