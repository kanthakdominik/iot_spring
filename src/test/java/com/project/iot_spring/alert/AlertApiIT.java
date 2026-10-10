package com.project.iot_spring.alert;

import com.jayway.jsonpath.JsonPath;
import com.project.iot_spring.ingest.MqttMessageDispatcher;
import com.project.iot_spring.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Alert rules (default rules of migration V2), cooldown, acknowledging, rule CRUD. */
class AlertApiIT extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-08T20:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MqttMessageDispatcher dispatcher;

    @Test
    void doseRateAboveThresholdRaisesAlertOncePerCooldown() throws Exception {
        // default rule: >= 0.5 uSv/h, cooldown 15 min
        dose(0, "2026-10-08T19:00:00Z", 0.30);
        dose(1, "2026-10-08T19:00:01Z", 0.62);
        dose(2, "2026-10-08T19:00:02Z", 0.80);    // within the cooldown
        dose(3, "2026-10-08T19:16:00Z", 0.55);    // cooldown over
        awaitAlerts();

        mvc.perform(get("/api/alerts").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                // newest first
                .andExpect(jsonPath("$.content[0].value").value(0.55))
                .andExpect(jsonPath("$.content[1].value").value(0.62))
                .andExpect(jsonPath("$.content[1].type").value("DOSE_RATE_ABOVE"))
                .andExpect(jsonPath("$.content[1].ruleName").value("Wysoki poziom promieniowania"))
                .andExpect(jsonPath("$.content[1].threshold").value(0.5))
                .andExpect(jsonPath("$.content[1].deviceId").value("esp32-1"))
                .andExpect(jsonPath("$.content[1].deviceName").value("esp32-1"))
                .andExpect(jsonPath("$.content[1].routeId").isNumber())
                .andExpect(jsonPath("$.content[1].ts").value("2026-10-08T19:00:01Z"))
                .andExpect(jsonPath("$.content[1].latitude").value(52.218269))
                .andExpect(jsonPath("$.content[1].acknowledgedAt").value(nullValue()));
        mvc.perform(get("/api/alerts/count").with(viewer()))
                .andExpect(jsonPath("$.open").value(2));

        // every alert is also in the event log
        assertThat(jdbc.sql("SELECT count(*) FROM device_events WHERE type = 'ALERT' AND severity = 'WARN'")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void acknowledging() throws Exception {
        dose(0, "2026-10-08T19:00:00Z", 0.7);
        dose(1, "2026-10-08T19:30:00Z", 0.7);
        awaitAlerts();
        long first = jdbc.sql("SELECT min(id) FROM alerts").query(Long.class).single();

        mvc.perform(post("/api/alerts/{id}/ack", first).with(csrf()).with(viewer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alerts/{id}/ack", first).with(csrf()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedBy").value(ADMIN_USER))
                .andExpect(jsonPath("$.acknowledgedAt").isString());
        mvc.perform(get("/api/alerts").param("state", "open").with(viewer()))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(get("/api/alerts/count").with(viewer()))
                .andExpect(jsonPath("$.open").value(1));

        mvc.perform(post("/api/alerts/ack-all").with(csrf()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged").value(1));
        mvc.perform(get("/api/alerts/count").with(viewer()))
                .andExpect(jsonPath("$.open").value(0));
        mvc.perform(get("/api/alerts").param("state", "all").with(viewer()))
                .andExpect(jsonPath("$.content", hasSize(2)));

        mvc.perform(post("/api/alerts/{id}/ack", 999_999).with(csrf()).with(admin()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/alerts").param("state", "closed").with(viewer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lowBatteryAndOfflineRaiseAlerts() throws Exception {
        dispatcher.dispatch("radmon/v1/esp32-1/status",
                "{\"online\":true,\"fw\":\"2.1.0\",\"r\":3,\"reset\":\"POWERON\",\"bat_mv\":3550,\"bat_pct\":20}", NOW);
        // 20 % is not below 20 %
        dispatcher.dispatch("radmon/v1/esp32-1/event", "{\"type\":\"battery\",\"mv\":3450,\"pct\":10}", NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/status", "{\"online\":false}", NOW.plusSeconds(60));
        // the retained Last Will delivered again (e.g. after a backend restart) is not a new change
        dispatcher.dispatch("radmon/v1/esp32-1/status", "{\"online\":false}", NOW.plusSeconds(3600));
        awaitAlerts();

        assertThat(jdbc.sql("SELECT type FROM alerts ORDER BY id").query(String.class).list())
                .containsExactly("BATTERY_BELOW", "DEVICE_OFFLINE");
        Map<String, Object> battery = jdbc.sql("SELECT value, threshold, message FROM alerts WHERE type = 'BATTERY_BELOW'")
                .query().singleRow();
        assertThat(battery.get("value")).isEqualTo(10.0);
        assertThat(battery.get("threshold")).isEqualTo(20.0);
    }

    @Test
    void ruleForAnotherDeviceOrDisabledRuleDoesNotFire() throws Exception {
        dose(0, "2026-10-08T19:00:00Z", 0.1);   // creates esp32-1
        dispatcher.dispatch("radmon/v1/esp32-2/telemetry", sample(1, 0, "2026-10-08T19:00:00Z", true), NOW);
        long doseRule = jdbc.sql("SELECT id FROM alert_rules WHERE type = 'DOSE_RATE_ABOVE'").query(Long.class).single();

        // only esp32-2, threshold 0.2
        mvc.perform(put("/api/alert-rules/{id}", doseRule).with(csrf()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dawka esp32-2\",\"type\":\"DOSE_RATE_ABOVE\",\"threshold\":0.2,"
                                + "\"deviceId\":\"esp32-2\",\"enabled\":true,\"cooldownMinutes\":0,\"notifyPush\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("esp32-2"))
                .andExpect(jsonPath("$.cooldownMinutes").value(0));

        dose(1, "2026-10-08T19:00:01Z", 0.9);
        awaitAlerts();
        assertThat(count("alerts")).isZero();

        dispatcher.dispatch("radmon/v1/esp32-2/telemetry",
                sample(1, 1, "2026-10-08T19:00:01Z", true, 52.2, 21.0, 0.3), NOW);
        dispatcher.dispatch("radmon/v1/esp32-2/telemetry",
                sample(1, 2, "2026-10-08T19:00:02Z", true, 52.2, 21.0, 0.3), NOW);
        awaitAlerts();
        // cooldown 0 = every sample
        assertThat(count("alerts")).isEqualTo(2);
    }

    @Test
    void ruleCrud() throws Exception {
        mvc.perform(get("/api/alert-rules").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].type").value("DOSE_RATE_ABOVE"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].cooldownMinutes").value(15))
                .andExpect(jsonPath("$[0].notifyPush").value(true))
                .andExpect(jsonPath("$[2].threshold").value(nullValue()));

        String id = JsonPath.read(mvc.perform(post("/api/alert-rules").with(csrf()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bardzo wysoki\",\"type\":\"DOSE_RATE_ABOVE\",\"threshold\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.threshold").value(5.0))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.cooldownMinutes").value(15))
                .andReturn().getResponse().getContentAsString(), "$.id").toString();

        mvc.perform(post("/api/alert-rules").with(csrf()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Zła\",\"type\":\"TEMPERATURE\",\"threshold\":5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/alert-rules").with(csrf()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bez progu\",\"type\":\"BATTERY_BELOW\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/alert-rules").with(csrf()).with(viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":\"DEVICE_OFFLINE\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/alert-rules/{id}", id).with(csrf()).with(admin()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/alert-rules/{id}", id).with(csrf()).with(admin()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/alert-rules").with(viewer()))
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void alertsNeedLogin() throws Exception {
        mvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/alerts/count")).andExpect(status().isUnauthorized());
    }

    private void dose(int seq, String ts, double usv) {
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry",
                sample(1, seq, ts, true, 52.218269, 21.011198, usv), NOW);
    }

    private static RequestPostProcessor viewer() {
        return user("viewer").roles("VIEWER");
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_USER).roles("ADMIN");
    }
}
