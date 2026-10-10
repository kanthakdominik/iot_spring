package com.project.iot_spring.route;

import com.jayway.jsonpath.JsonPath;
import com.project.iot_spring.ingest.MqttMessageDispatcher;
import com.project.iot_spring.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
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
    void loggedInViewerCanReadRoutesAndMeasurements() throws Exception {
        mvc.perform(get("/api/routes").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].deviceId").value("esp32-1"))
                .andExpect(jsonPath("$.content[0].deviceName").value("esp32-1"))
                .andExpect(jsonPath("$.content[0].deviceRouteNo").value(1))
                .andExpect(jsonPath("$.content[0].sampleCount").value(3))
                .andExpect(jsonPath("$.content[0].fixCount").value(2))
                .andExpect(jsonPath("$.content[0].shareToken").value(nullValue()));

        mvc.perform(get("/api/routes/{id}/measurements", routeId).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                // sorted by time, even though samples arrived out of order
                .andExpect(jsonPath("$[0].seq").value(0))
                .andExpect(jsonPath("$[1].seq").value(1))
                .andExpect(jsonPath("$[2].gpsFix").value(false))
                .andExpect(jsonPath("$[2].latitude").value(nullValue()));
    }

    @Test
    void readingRequiresLogin() throws Exception {
        mvc.perform(get("/api/routes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/routes/{id}", routeId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/routes/{id}/measurements", routeId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized());
    }

    @Test
    void measurementsAfterGivenTimeOnly() throws Exception {
        mvc.perform(get("/api/routes/{id}/measurements", routeId).param("after", "2026-10-08T19:00:00Z")
                        .with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].seq").value(1));
    }

    @Test
    void unknownRouteGives404() throws Exception {
        mvc.perform(get("/api/routes/{id}", 999_999).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void changesRequireLogin() throws Exception {
        mvc.perform(delete("/api/routes/{id}", routeId).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changesRequireAdminRole() throws Exception {
        mvc.perform(delete("/api/routes/{id}", routeId).with(csrf()).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/routes/{id}/share", routeId).with(csrf()).with(user("viewer").roles("VIEWER")))
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
                .andExpect(jsonPath("$.description").value("Spacer z Am-241"))
                .andExpect(jsonPath("$.sampleCount").value(3));
    }

    @Test
    void adminCanDeleteMeasurementAndStatisticsAreRecomputed() throws Exception {
        long measurementId = jdbc.sql("SELECT id FROM measurements WHERE seq = 2").query(Long.class).single();

        mvc.perform(delete("/api/routes/{id}/measurements/{mid}", routeId, measurementId)
                        .with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/routes/{id}", routeId).with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.sampleCount").value(2))
                .andExpect(jsonPath("$.fixCount").value(2))
                .andExpect(jsonPath("$.maxUsv").value(0.1))
                .andExpect(jsonPath("$.preview.track", hasSize(2)));
        assertStatsClean();
    }

    @Test
    void adminCanDeleteRouteWithAllData() throws Exception {
        mvc.perform(delete("/api/routes/{id}", routeId).with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/routes").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void adminCanAddAndRemoveMarkers() throws Exception {
        mvc.perform(post("/api/routes/{id}/markers", routeId)
                        .with(csrf()).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Am-241\",\"latitude\":52.2183,\"longitude\":21.0109}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("user"));

        mvc.perform(get("/api/routes/{id}/markers", routeId).with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("Am-241"));
    }

    @Test
    void devicesAreListedAndCommandsNeedMqtt() throws Exception {
        mvc.perform(get("/api/devices").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("esp32-1"))
                .andExpect(jsonPath("$[0].online").value(true))
                .andExpect(jsonPath("$[0].currentRouteNo").value(1))
                .andExpect(jsonPath("$[0].currentRouteId").value(routeId))
                .andExpect(jsonPath("$[0].routeCount").value(1));

        mvc.perform(get("/api/devices/{id}", "esp32-1").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("esp32-1"));
        mvc.perform(get("/api/devices/{id}", "nope").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isNotFound());

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

    // ------------------------------------------------------------------ sharing

    @Test
    void sharedRouteIsPublicUntilTheLinkIsRemoved() throws Exception {
        String token = JsonPath.read(
                mvc.perform(post("/api/routes/{id}/share", routeId).with(csrf()).with(user("admin").roles("ADMIN")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.token").isString())
                        .andReturn().getResponse().getContentAsString(), "$.token");
        assertThat(token).matches("^[A-Za-z0-9_-]{22}$");

        // the same token is returned again
        mvc.perform(post("/api/routes/{id}/share", routeId).with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.token").value(token));

        // only the admin sees the token
        mvc.perform(get("/api/routes/{id}", routeId).with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.shareToken").value(token));
        mvc.perform(get("/api/routes/{id}", routeId).with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.shareToken").value(nullValue()));

        // anonymous access through the token
        mvc.perform(get("/api/public/routes/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(routeId))
                .andExpect(jsonPath("$.shareToken").value(nullValue()));
        mvc.perform(get("/api/public/routes/{token}/measurements", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get("/api/public/routes/{token}/markers", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(delete("/api/routes/{id}/share", routeId).with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/public/routes/{token}", token)).andExpect(status().isNotFound());
    }

    @Test
    void unknownShareTokenGives404() throws Exception {
        mvc.perform(get("/api/public/routes/{token}", "AAAAAAAAAAAAAAAAAAAAAA")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/routes/{token}/measurements", "not-a-token")).andExpect(status().isNotFound());
    }

    private void assertStatsClean() {
        assertThat(
                jdbc.sql("SELECT stats_dirty FROM routes WHERE id = :id").param("id", routeId)
                        .query(Boolean.class).single()).isFalse();
    }
}
