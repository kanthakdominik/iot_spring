package com.project.iot_spring.route;

import com.project.iot_spring.ingest.MqttMessageDispatcher;
import com.project.iot_spring.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/routes: paging, filters and sorting. */
class RouteListIT extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-08T20:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MqttMessageDispatcher dispatcher;

    @Autowired
    private RouteStatsService statsService;

    @BeforeEach
    void createRoutes() {
        // esp32-1 #1: 2026-10-01, 3 samples
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(1, 0, "2026-10-01T10:00:00Z", true), NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(1, 1, "2026-10-01T10:00:01Z", true), NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(1, 2, "2026-10-01T10:00:02Z", true), NOW);
        // esp32-1 #2 "Las Kabacki": 2026-10-05, 1 sample
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(2, 0, "2026-10-05T10:00:00Z", true), NOW);
        jdbc.sql("UPDATE routes SET name = 'Las Kabacki' WHERE device_route_no = 2 AND device_id = 'esp32-1'")
                .update();
        // esp32-2 #1: 2026-10-03, 2 samples with a higher dose rate
        dispatcher.dispatch("radmon/v1/esp32-2/telemetry",
                sample(1, 0, "2026-10-03T10:00:00Z", true, 52.1, 21.1, 0.3), NOW);
        dispatcher.dispatch("radmon/v1/esp32-2/telemetry",
                sample(1, 1, "2026-10-03T10:00:01Z", true, 52.1001, 21.1, 0.4), NOW);
        // esp32-2 #2: started, but no samples yet (started_at = NULL)
        dispatcher.dispatch("radmon/v1/esp32-2/event", "{\"type\":\"route-started\",\"r\":2,\"reason\":\"cmd\"}", NOW);

        new RouteStatsJob(statsService).runOnce();
    }

    @Test
    void newestFirstByDefaultWithRoutesWithoutSamplesLast() throws Exception {
        mvc.perform(viewer(get("/api/routes")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name",
                        contains("Las Kabacki", "esp32-2 #1", "esp32-1 #1", "esp32-2 #2")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(24))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void ascendingKeepsNullsLast() throws Exception {
        mvc.perform(viewer(get("/api/routes").param("dir", "asc")))
                .andExpect(jsonPath("$.content[*].name",
                        contains("esp32-1 #1", "esp32-2 #1", "Las Kabacki", "esp32-2 #2")));
    }

    @Test
    void paging() throws Exception {
        mvc.perform(viewer(get("/api/routes").param("page", "1").param("size", "3")))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name").value("esp32-2 #2"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(viewer(get("/api/routes").param("size", "1000")))
                .andExpect(jsonPath("$.size").value(100));
        mvc.perform(viewer(get("/api/routes").param("page", "-1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filters() throws Exception {
        mvc.perform(viewer(get("/api/routes").param("deviceId", "esp32-2")))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(viewer(get("/api/routes").param("q", "kabacki")))
                .andExpect(jsonPath("$.content[*].name", contains("Las Kabacki")));
        // q also matches the device name
        mvc.perform(viewer(get("/api/routes").param("q", "esp32-2")))
                .andExpect(jsonPath("$.totalElements").value(2));
        // LIKE wildcards are literal
        mvc.perform(viewer(get("/api/routes").param("q", "%")))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(viewer(get("/api/routes")
                        .param("from", "2026-10-02T00:00:00Z").param("to", "2026-10-04T00:00:00Z")))
                .andExpect(jsonPath("$.content[*].name", contains("esp32-2 #1")));
        // plain dates: whole days in the application time zone
        mvc.perform(viewer(get("/api/routes").param("from", "2026-10-03").param("to", "2026-10-05")))
                .andExpect(jsonPath("$.content[*].name", contains("Las Kabacki", "esp32-2 #1")));
    }

    @Test
    void sortingByStatistics() throws Exception {
        mvc.perform(viewer(get("/api/routes").param("sort", "maxUsv")))
                .andExpect(jsonPath("$.content[0].name").value("esp32-2 #1"))
                .andExpect(jsonPath("$.content[0].maxUsv").value(0.4))
                .andExpect(jsonPath("$.content[3].name").value("esp32-2 #2"));
        mvc.perform(viewer(get("/api/routes").param("sort", "samples").param("dir", "asc")))
                .andExpect(jsonPath("$.content[*].sampleCount", contains(0, 1, 2, 3)));
        mvc.perform(viewer(get("/api/routes").param("sort", "name").param("dir", "asc")))
                .andExpect(jsonPath("$.content[*].name",
                        contains("esp32-1 #1", "esp32-2 #1", "esp32-2 #2", "Las Kabacki")));
        mvc.perform(viewer(get("/api/routes").param("sort", "distance")))
                .andExpect(jsonPath("$.content[0].name").value("esp32-2 #1"));
    }

    @Test
    void invalidSortGives400() throws Exception {
        mvc.perform(viewer(get("/api/routes").param("sort", "id; DROP TABLE routes")))
                .andExpect(status().isBadRequest());
        mvc.perform(viewer(get("/api/routes").param("dir", "sideways")))
                .andExpect(status().isBadRequest());
        mvc.perform(viewer(get("/api/routes").param("from", "yesterday")))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder viewer(MockHttpServletRequestBuilder request) {
        return request.with(user("viewer").roles("VIEWER"));
    }
}
