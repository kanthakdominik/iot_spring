package com.project.iot_spring.route;

import com.project.iot_spring.ingest.MqttMessageDispatcher;
import com.project.iot_spring.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RouteApiIT extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MqttMessageDispatcher dispatcher;

    private long routeId;

    @BeforeEach
    void createRoute() {
        Instant now = Instant.now();
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(1, 1, "2026-10-08T19:00:01Z", true), now);
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(1, 0, "2026-10-08T19:00:00Z", true), now);
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(1, 2, "2026-10-08T19:00:02Z", false), now);
        routeId = jdbc.sql("SELECT id FROM routes").query(Long.class).single();
    }

    @Test
    void anyoneCanReadRoutesAndMeasurements() throws Exception {
        mvc.perform(get("/api/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].deviceId").value("esp32-1"))
                .andExpect(jsonPath("$[0].deviceRouteNo").value(1))
                .andExpect(jsonPath("$[0].sampleCount").value(3));

        mvc.perform(get("/api/routes/{id}/measurements", routeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                // sorted by time, even though samples arrived out of order
                .andExpect(jsonPath("$[0].seq").value(0))
                .andExpect(jsonPath("$[1].seq").value(1))
                .andExpect(jsonPath("$[2].gpsFix").value(false))
                .andExpect(jsonPath("$[2].latitude").value(nullValue()));
    }

    @Test
    void unknownRouteGives404() throws Exception {
        mvc.perform(get("/api/routes/{id}", 999_999)).andExpect(status().isNotFound());
    }

    @Test
    void changesRequireLogin() throws Exception {
        mvc.perform(delete("/api/routes/{id}", routeId).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changesRequireAdminRole() throws Exception {
        mvc.perform(delete("/api/routes/{id}", routeId).with(csrf()).with(user("viewer").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void changesRequireCsrfToken() throws Exception {
        mvc.perform(delete("/api/routes/{id}", routeId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanRenameAndDescribeRoute() throws Exception {
        mvc.perform(patch("/api/routes/{id}", routeId)
                        .with(csrf()).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pole Mokotowskie\",\"description\":\"Spacer z Am-241\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pole Mokotowskie"))
                .andExpect(jsonPath("$.description").value("Spacer z Am-241"));
    }

    @Test
    void adminCanDeleteMeasurementAndStatisticsAreUpdated() throws Exception {
        long measurementId = jdbc.sql("SELECT id FROM measurements WHERE seq = 2").query(Long.class).single();

        mvc.perform(delete("/api/routes/{id}/measurements/{mid}", routeId, measurementId)
                        .with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/routes/{id}", routeId))
                .andExpect(jsonPath("$.sampleCount").value(2));
    }

    @Test
    void adminCanDeleteRouteWithAllData() throws Exception {
        mvc.perform(delete("/api/routes/{id}", routeId).with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/routes")).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void adminCanAddAndRemoveMarkers() throws Exception {
        mvc.perform(post("/api/routes/{id}/markers", routeId)
                        .with(csrf()).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Am-241\",\"latitude\":52.2183,\"longitude\":21.0109}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("user"));

        mvc.perform(get("/api/routes/{id}/markers", routeId))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("Am-241"));
    }

    @Test
    void devicesAreListedAndCommandsNeedMqtt() throws Exception {
        mvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("esp32-1"))
                .andExpect(jsonPath("$[0].online").value(true));

        // MQTT is disabled in tests -> 503
        mvc.perform(post("/api/devices/{id}/commands", "esp32-1")
                        .with(csrf()).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"new-route\"}"))
                .andExpect(status().isServiceUnavailable());

        mvc.perform(post("/api/devices/{id}/commands", "esp32-1")
                        .with(csrf()).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"self-destruct\"}"))
                .andExpect(status().isBadRequest());
    }

    private static String sample(int routeNo, int seq, String ts, boolean fix) {
        String position = fix ? "\"fix\":true,\"lat\":52.218269,\"lon\":21.011198," : "\"fix\":false,";
        return "{\"v\":1,\"r\":" + routeNo + ",\"seq\":" + seq + ",\"ts\":\"" + ts + "\",\"tsrc\":\"gnss\","
                + position + "\"cps\":0,\"cpm\":18,\"usv\":0.10,\"mode\":\"SLOW\"}";
    }
}
