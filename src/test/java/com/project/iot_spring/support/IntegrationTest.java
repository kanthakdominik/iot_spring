package com.project.iot_spring.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests: full application context + TimescaleDB (Testcontainers) + MockMvc.
 * MQTT is disabled; tests call the ingest services directly.
 */
@SpringBootTest(properties = {
        "app.admin.username=" + IntegrationTest.ADMIN_USER,
        "app.admin.password=" + IntegrationTest.ADMIN_PASSWORD,
        "mqtt.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    public static final String ADMIN_USER = "admin";
    public static final String ADMIN_PASSWORD = "test-password";

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE route_markers, measurements, routes, devices RESTART IDENTITY CASCADE").update();
    }
}
