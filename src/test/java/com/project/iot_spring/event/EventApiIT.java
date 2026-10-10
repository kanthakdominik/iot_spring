package com.project.iot_spring.event;

import com.project.iot_spring.ingest.MqttMessageDispatcher;
import com.project.iot_spring.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Event log written by ingest, device health history and GSM signal. */
class EventApiIT extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-08T20:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MqttMessageDispatcher dispatcher;

    @Test
    void deviceLifecycleIsLogged() throws Exception {
        dispatcher.dispatch("radmon/v1/esp32-1/status",
                "{\"online\":true,\"fw\":\"2.1.0\",\"r\":3,\"reset\":\"POWERON\",\"bat_mv\":3980,\"bat_pct\":70,\"csq\":18}",
                NOW);
        // retained status delivered again: no second DEVICE_ONLINE
        dispatcher.dispatch("radmon/v1/esp32-1/status",
                "{\"online\":true,\"fw\":\"2.1.0\",\"r\":3,\"reset\":\"POWERON\",\"bat_mv\":3980,\"bat_pct\":70,\"csq\":18}",
                NOW.plusSeconds(1));
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(3, 0, "2026-10-08T19:59:00Z", true), NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/event",
                "{\"type\":\"marker\",\"r\":3,\"seq\":1,\"ts\":\"2026-10-08T19:59:01Z\",\"fix\":false,\"label\":\"button\"}",
                NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/event",
                "{\"type\":\"error\",\"code\":\"sensor-timeout\",\"msg\":\"no data from MightyOhm\"}", NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/event", "{\"type\":\"route-started\",\"r\":4,\"reason\":\"cmd\"}", NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/status", "{\"online\":false}", NOW.plusSeconds(120));
        awaitAlerts();

        List<String> types = jdbc.sql("SELECT type FROM device_events ORDER BY id").query(String.class).list();
        assertThat(types).containsExactly("DEVICE_ONLINE", "BATTERY", "ROUTE_STARTED", "MARKER", "DEVICE_ERROR",
                "ROUTE_STARTED", "DEVICE_OFFLINE", "ALERT");

        mvc.perform(get("/api/events").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(8))
                .andExpect(jsonPath("$.content[0].id").isNumber())
                .andExpect(jsonPath("$.content[0].deviceId").value("esp32-1"))
                .andExpect(jsonPath("$.content[0].deviceName").value("esp32-1"))
                .andExpect(jsonPath("$.content[0].ts").isString())
                .andExpect(jsonPath("$.content[0].message").isString());

        mvc.perform(get("/api/events").param("type", "DEVICE_ERROR").with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.content[0].routeId").value(nullValue()));
        mvc.perform(get("/api/events").param("type", "MARKER").with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.content[0].routeId").isNumber());
        mvc.perform(get("/api/events").param("deviceId", "esp32-2").with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/events").param("size", "3").param("page", "2").with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/events").param("type", "NOPE").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batteryEventsAreLoggedOnlyOnBiggerChanges() {
        battery(4000, 80);   // first reading -> event
        battery(3990, 79);
        battery(3980, 77);
        battery(3960, 75);   // 5 points below the last logged value -> event
        battery(3950, 74);
        battery(3700, 40);   // -> event

        List<Integer> logged = jdbc.sql("""
                        SELECT (data ->> 'pct')::int FROM device_events WHERE type = 'BATTERY' ORDER BY id""")
                .query(Integer.class).list();
        assertThat(logged).containsExactly(80, 75, 40);
        // ... but every reading is in the health history
        assertThat(count("device_health")).isEqualTo(6);
    }

    @Test
    void healthHistoryAndSignalQuality() throws Exception {
        dispatcher.dispatch("radmon/v1/esp32-1/status",
                "{\"online\":true,\"fw\":\"2.1.0\",\"r\":3,\"reset\":\"POWERON\",\"bat_mv\":3980,\"bat_pct\":70,\"csq\":18}",
                Instant.now().minusSeconds(600));
        dispatcher.dispatch("radmon/v1/esp32-1/event", "{\"type\":\"battery\",\"mv\":3950,\"pct\":66,\"csq\":99}",
                Instant.now().minusSeconds(60));

        Map<String, Object> device = jdbc.sql("SELECT signal_csq, battery_pct FROM devices").query().singleRow();
        // csq 99 = unknown: the last known value stays
        assertThat(((Number) device.get("signal_csq")).intValue()).isEqualTo(18);
        assertThat(device.get("battery_pct")).isEqualTo(66);

        mvc.perform(get("/api/devices/{id}/health", "esp32-1").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].batteryPct", contains(70, 66)))
                .andExpect(jsonPath("$[0].batteryMv").value(3980))
                .andExpect(jsonPath("$[0].signalCsq").value(18))
                .andExpect(jsonPath("$[1].signalCsq").value(nullValue()))
                .andExpect(jsonPath("$[0].ts").isString());

        mvc.perform(get("/api/devices/{id}", "esp32-1").with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.signalCsq").value(18))
                .andExpect(jsonPath("$.signalAt").isString())
                .andExpect(jsonPath("$.batteryPct").value(66));

        // a range in the past has no points
        mvc.perform(get("/api/devices/{id}/health", "esp32-1")
                        .param("from", "2020-01-01T00:00:00Z").param("to", "2020-01-02T00:00:00Z")
                        .with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/devices/{id}/health", "unknown").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isNotFound());
    }

    private int readings;

    private void battery(int mv, int pct) {
        dispatcher.dispatch("radmon/v1/esp32-1/event",
                "{\"type\":\"battery\",\"mv\":" + mv + ",\"pct\":" + pct + "}", NOW.plusSeconds(60L * readings++));
    }
}
