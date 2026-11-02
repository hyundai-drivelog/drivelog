package com.drivelog.server;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import com.fasterxml.jackson.databind.JsonNode;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerPostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void connectsToPostgresAndServesBothHealthEndpoints() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }

        var health = rest.getForEntity("/api/v1/health", JsonNode.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).isNotNull();
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
        assertThat(health.getBody().path("service").asText()).isEqualTo("drivelog-server");

        var actuator = rest.getForEntity("/actuator/health", JsonNode.class);
        assertThat(actuator.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actuator.getBody()).isNotNull();
        assertThat(actuator.getBody().path("status").asText()).isEqualTo("UP");
        assertThat(actuator.getBody().has("components")).isFalse();
    }
}
