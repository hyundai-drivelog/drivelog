package com.drivelog.server.domain.wash;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.drivelog.server.domain.wash.WashForecast.Availability;
import com.drivelog.server.domain.wash.WashForecast.Day;
import com.drivelog.server.domain.wash.WashForecast.RecommendationStatus;
import com.drivelog.server.domain.wash.WashForecast.WeatherType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WashController.class)
class WashControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean WashRecommendationService service;

    @Test void anonymousSeoulResponseIncludesNullableForecastFields() throws Exception {
        var response = new WashRecommendationResponse("SEOUL", "서울", RecommendationStatus.UNKNOWN,
                "이번 주 날씨 정보가 없어요", "예보가 준비되면 확인할 수 있어요.", "날씨 정보 없음", "",
                List.of(new Day(LocalDate.of(2026, 11, 9), WeatherType.UNKNOWN, null, false)),
                "UNAVAILABLE", null, null, false, Availability.NO_DATA);
        when(service.recommendation(WashRegion.SEOUL)).thenReturn(response);
        mvc.perform(get("/api/v1/wash/recommendation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionCode").value("SEOUL"))
                .andExpect(jsonPath("$.recommendationStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.dailyForecasts[0].date").value("2026-11-09"))
                .andExpect(jsonPath("$.dailyForecasts[0].precipitationProbability").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.forecastIssuedAt").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test void unsupportedRegionReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/wash/recommendation").param("region", "UNKNOWN_REGION"))
                .andExpect(status().isBadRequest());
    }
}
