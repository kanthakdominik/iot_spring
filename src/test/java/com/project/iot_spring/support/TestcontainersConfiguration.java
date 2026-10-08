package com.project.iot_spring.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL 18 + TimescaleDB in Docker for integration tests - the same image as in production.
 * The container is shared by all tests that use the same (cached) Spring context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String TIMESCALE_IMAGE = "timescale/timescaledb:2.30.0-pg18";

    @Bean
    @ServiceConnection
    PostgreSQLContainer timescaleContainer() {
        return new PostgreSQLContainer(DockerImageName.parse(TIMESCALE_IMAGE).asCompatibleSubstituteFor("postgres"));
    }
}
