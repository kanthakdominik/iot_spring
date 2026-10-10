package com.project.iot_spring.support;

import com.project.iot_spring.alert.AlertRuleService;
import com.project.iot_spring.alert.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

/**
 * Base class for integration tests: full application context + TimescaleDB (Testcontainers) + MockMvc.
 * MQTT is disabled; tests call the ingest services directly.
 * Before every test the data tables are emptied, users other than the initial admin are removed and the
 * default alert rules of migration V2 are restored.
 */
@SpringBootTest(properties = {
        "app.admin.username=" + IntegrationTest.ADMIN_USER,
        "app.admin.password=" + IntegrationTest.ADMIN_PASSWORD,
        "mqtt.enabled=false",
        // statistics are recomputed explicitly in tests (RouteStatsJob.runOnce / RouteStatsService)
        "app.stats.job.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    public static final String ADMIN_USER = "admin";
    public static final String ADMIN_PASSWORD = "test-password";

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected AlertService alertService;

    @Autowired
    protected AlertRuleService alertRuleService;

    @BeforeEach
    void cleanDatabase() {
        // alerts of the previous test are evaluated asynchronously - let them finish first
        alertService.awaitIdle(Duration.ofSeconds(10));
        jdbc.sql("""
                TRUNCATE route_markers, measurements, routes, devices, device_health, device_events,
                         alerts, alert_rules
                RESTART IDENTITY CASCADE""").update();
        jdbc.sql("""
                INSERT INTO alert_rules (name, type, threshold) VALUES
                    ('Wysoki poziom promieniowania', 'DOSE_RATE_ABOVE', 0.5),
                    ('Niska bateria', 'BATTERY_BELOW', 20),
                    ('Urządzenie offline', 'DEVICE_OFFLINE', NULL)""").update();
        alertRuleService.reload();
        jdbc.sql("DELETE FROM users WHERE username <> :admin").param("admin", ADMIN_USER).update();
        jdbc.sql("UPDATE users SET role = 'ADMIN', preferences = '{}'::jsonb WHERE username = :admin")
                .param("admin", ADMIN_USER).update();
    }

    /** Waits until alert rules were evaluated for everything published so far. */
    protected void awaitAlerts() {
        alertService.awaitIdle(Duration.ofSeconds(10));
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /** Telemetry frame v1 with a fixed position and dose rate 0.10 uSv/h. */
    public static String sample(int routeNo, int seq, String ts, boolean fix) {
        return sample(routeNo, seq, ts, fix, 52.218269, 21.011198, 0.10);
    }

    public static String sample(int routeNo, int seq, String ts, boolean fix, double lat, double lon, double usv) {
        String position = fix ? "\"fix\":true,\"lat\":" + lat + ",\"lon\":" + lon + "," : "\"fix\":false,";
        return "{\"v\":1,\"r\":" + routeNo + ",\"seq\":" + seq + ",\"ts\":\"" + ts + "\",\"tsrc\":\"gnss\","
                + position + "\"cps\":0,\"cpm\":18,\"usv\":" + usv + ",\"mode\":\"SLOW\"}";
    }
}
