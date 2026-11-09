package com.drivelog.server.domain.wash;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:wash;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;INIT=CREATE SCHEMA IF NOT EXISTS drivelog",
        "KMA_SERVICE_KEY="})
class WashNoKeyIntegrationTest {
    @Autowired MockMvc mvc;

    @Test void anonymousRequestWithoutKeyReturnsUnknownWithoutDemoForecast() throws Exception {
        mvc.perform(get("/api/v1/wash/recommendation").param("region", "SEOUL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.availability").value("NO_DATA"))
                .andExpect(jsonPath("$.source").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.dailyForecasts.length()").value(7))
                .andExpect(jsonPath("$.dailyForecasts[0].precipitationProbability").value(org.hamcrest.Matchers.nullValue()));
    }
}
