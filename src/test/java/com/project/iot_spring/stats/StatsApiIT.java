package com.project.iot_spring.stats;

import com.project.iot_spring.ingest.MqttMessageDispatcher;
import com.project.iot_spring.route.RouteStatsService;
import com.project.iot_spring.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/stats/summary, /api/stats/activity, /api/map/grid, /api/search. */
class StatsApiIT extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-09T20:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MqttMessageDispatcher dispatcher;

    @Autowired
    private RouteStatsService statsService;

    @BeforeEach
    void data() {
        // route 1 (esp32-1 #1): 2026-10-08 evening UTC = still 2026-10-08 in Warsaw, two cells
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry",
                sample(1, 0, "2026-10-08T20:00:00Z", true, 52.2001, 21.0001, 0.10), NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry",
                sample(1, 1, "2026-10-08T20:00:01Z", true, 52.2002, 21.0002, 0.20), NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry",
                sample(1, 2, "2026-10-08T20:00:02Z", true, 52.3001, 21.0001, 0.40), NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry",
                sample(1, 3, "2026-10-08T20:00:03Z", false, 0, 0, 0.30), NOW);
        // route 2 (esp32-2 #1, "Las Kabacki"): 22:30 UTC = 2026-10-09 00:30 in Warsaw
        dispatcher.dispatch("radmon/v1/esp32-2/telemetry",
                sample(1, 0, "2026-10-08T22:30:00Z", true, 52.2003, 21.0003, 0.45), NOW);
        jdbc.sql("UPDATE routes SET name = 'Las Kabacki' WHERE device_id = 'esp32-2'").update();
        jdbc.sql("UPDATE devices SET name = 'Miernik Kabaty' WHERE id = 'esp32-2'").update();
        dispatcher.dispatch("radmon/v1/esp32-2/event",
                "{\"type\":\"marker\",\"r\":1,\"seq\":1,\"ts\":\"2026-10-08T22:30:01Z\",\"fix\":false,\"label\":\"Am-241\"}",
                NOW);
        for (Long id : statsService.findDirty(10)) {
            statsService.recompute(id);
        }
    }

    @Test
    void summary() throws Exception {
        mvc.perform(get("/api/stats/summary").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeCount").value(2))
                .andExpect(jsonPath("$.deviceCount").value(2))
                .andExpect(jsonPath("$.devicesOnline").value(2))
                .andExpect(jsonPath("$.sampleCount").value(5))
                // 13 m; the 11 km jump within one second is a GPS error and is not counted
                .andExpect(jsonPath("$.distanceM", closeTo(13.04, 0.01)))
                .andExpect(jsonPath("$.doseUsv").isNumber())
                .andExpect(jsonPath("$.maxUsv.value").value(0.45))
                .andExpect(jsonPath("$.maxUsv.routeName").value("Las Kabacki"))
                .andExpect(jsonPath("$.maxUsv.routeId").isNumber())
                .andExpect(jsonPath("$.maxUsv.ts").value("2026-10-08T22:30:00Z"))
                .andExpect(jsonPath("$.openAlerts").value(0))
                .andExpect(jsonPath("$.lastRoute.name").value("Las Kabacki"))
                .andExpect(jsonPath("$.lastRoute.preview.track", hasSize(1)))
                .andExpect(jsonPath("$.lastRoute.shareToken").value(nullValue()));
    }

    @Test
    void summaryOfEmptyDatabase() throws Exception {
        jdbc.sql("TRUNCATE routes, devices RESTART IDENTITY CASCADE").update();
        mvc.perform(get("/api/stats/summary").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeCount").value(0))
                .andExpect(jsonPath("$.distanceM").value(0.0))
                .andExpect(jsonPath("$.maxUsv").value(nullValue()))
                .andExpect(jsonPath("$.lastRoute").value(nullValue()));
    }

    @Test
    void activityPerLocalDay() throws Exception {
        mvc.perform(get("/api/stats/activity").param("from", "2026-10-01").param("to", "2026-10-31")
                        .with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].date", contains("2026-10-08", "2026-10-09")))
                .andExpect(jsonPath("$[0].samples").value(4))
                .andExpect(jsonPath("$[0].routes").value(1))
                .andExpect(jsonPath("$[0].maxUsv").value(0.4))
                .andExpect(jsonPath("$[1].samples").value(1))
                .andExpect(jsonPath("$[1].maxUsv").value(0.45));

        mvc.perform(get("/api/stats/activity").param("from", "2020-01-01").param("to", "2020-12-31")
                        .with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void mapGrid() throws Exception {
        // zoom 12: cell = 360 / 4096 / 16 = 0.0054931640625 deg
        mvc.perform(get("/api/map/grid")
                        .param("south", "52.0").param("west", "20.9").param("north", "52.4").param("east", "21.1")
                        .param("zoom", "12")
                        .with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cellDeg").value(0.0054931640625))
                .andExpect(jsonPath("$.cells", hasSize(2)))
                // biggest count first: 3 samples (2 from esp32-1, 1 from esp32-2) in the cell at 52.2 / 21.0
                .andExpect(jsonPath("$.cells[0].count").value(3))
                .andExpect(jsonPath("$.cells[0].lat", closeTo(52.196044921875, 1e-9)))
                .andExpect(jsonPath("$.cells[0].lon", closeTo(20.994873046875, 1e-9)))
                .andExpect(jsonPath("$.cells[0].maxUsv").value(0.45))
                .andExpect(jsonPath("$.cells[0].avgUsv", closeTo(0.25, 1e-9)))
                .andExpect(jsonPath("$.cells[1].count").value(1));

        mvc.perform(get("/api/map/grid")
                        .param("south", "52.0").param("west", "20.9").param("north", "52.4").param("east", "21.1")
                        .param("zoom", "12").param("deviceId", "esp32-2")
                        .with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.cells", hasSize(1)))
                .andExpect(jsonPath("$.cells[0].count").value(1));

        mvc.perform(get("/api/map/grid")
                        .param("south", "52.0").param("west", "20.9").param("north", "52.4").param("east", "21.1")
                        .param("zoom", "12").param("from", "2026-10-09")
                        .with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.cells", hasSize(1)));

        // zoom below 3 is clamped
        mvc.perform(get("/api/map/grid")
                        .param("south", "-90").param("west", "-180").param("north", "90").param("east", "180")
                        .param("zoom", "0")
                        .with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.cellDeg").value(2.8125))
                .andExpect(jsonPath("$.cells", hasSize(1)))
                .andExpect(jsonPath("$.cells[0].count").value(4));

        mvc.perform(get("/api/map/grid").param("south", "52.0").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search() throws Exception {
        mvc.perform(get("/api/search").param("q", "kab").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[*].name", contains("Las Kabacki")))
                .andExpect(jsonPath("$.routes[0].deviceId").value("esp32-2"))
                .andExpect(jsonPath("$.routes[0].startedAt").value("2026-10-08T22:30:00Z"))
                .andExpect(jsonPath("$.devices[*].name", contains("Miernik Kabaty")))
                .andExpect(jsonPath("$.markers", hasSize(0)));

        mvc.perform(get("/api/search").param("q", "am-2").with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.markers[0].label").value("Am-241"))
                .andExpect(jsonPath("$.markers[0].routeId").isNumber())
                .andExpect(jsonPath("$.markers[0].ts").value("2026-10-08T22:30:01Z"));

        mvc.perform(get("/api/search").param("q", "esp32").with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.devices", hasSize(2)))
                .andExpect(jsonPath("$.routes[*].name", contains("esp32-1 #1")));

        // too short
        mvc.perform(get("/api/search").param("q", "k").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes", hasSize(0)))
                .andExpect(jsonPath("$.devices", hasSize(0)));
    }

    @Test
    void statisticsNeedLogin() throws Exception {
        mvc.perform(get("/api/stats/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/search").param("q", "kab")).andExpect(status().isUnauthorized());
    }
}
