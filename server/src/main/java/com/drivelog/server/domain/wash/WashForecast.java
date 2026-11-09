package com.drivelog.server.domain.wash;

import java.time.LocalDate;
import java.time.LocalDateTime;

final class WashForecast {
    enum WeatherType { SUNNY, CLOUDY, RAINY, UNKNOWN }
    enum RecommendationStatus { RECOMMENDED, POSTPONE, UNKNOWN }
    enum Availability { AVAILABLE, NO_DATA, ERROR }

    record ShortSlot(LocalDateTime time, Integer pop, Integer pty, Integer sky, String pcp) {
        boolean precipitation() {
            if (pty != null && pty > 0) return true;
            if (pcp == null || pcp.isBlank() || pcp.equals("강수없음") || pcp.equals("0") || pcp.equals("0mm")) return false;
            // PCP can be a range or a qualitative amount, e.g. "1mm 미만".
            return pcp.contains("mm") || pcp.contains("강수");
        }
        boolean complete() { return pop != null && pty != null; }
    }

    record MidDay(LocalDate date, Integer amPop, Integer pmPop, String amWeather, String pmWeather) {
        Integer maxPop() {
            if (amPop == null) return pmPop;
            if (pmPop == null) return amPop;
            return Math.max(amPop, pmPop); // Conservative display value; never average half-days.
        }
        WeatherType weather() {
            String both = (amWeather == null ? "" : amWeather) + " " + (pmWeather == null ? "" : pmWeather);
            if (both.contains("비") || both.contains("눈")) return WeatherType.RAINY;
            if (both.contains("흐림") || both.contains("구름")) return WeatherType.CLOUDY;
            if (both.contains("맑음")) return WeatherType.SUNNY;
            return WeatherType.UNKNOWN;
        }
    }

    record Day(LocalDate date, WeatherType weatherType, Integer precipitationProbability,
               boolean highlighted) {}

    private WashForecast() {}
}
