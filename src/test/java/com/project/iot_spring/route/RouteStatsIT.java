package com.project.iot_spring.route;

import com.project.iot_spring.ingest.MqttMessageDispatcher;
import com.project.iot_spring.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RouteStatsJob / RouteStatsService on a small dataset computed by hand. */
class RouteStatsIT extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-08T20:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MqttMessageDispatcher dispatcher;

    @Autowired
    private RouteStatsService statsService;

    /**
     * <pre>
     * seq  t [s]  fix  position             usv   segment
     *  0      0   yes  52.000, 21.000       0.1
     *  1      1   yes  52.000, 21.001       0.2   68.46 m
     *  2      2   no   -                    0.3
     *  3      3   yes  52.001, 21.001       0.4   111.19 m (from seq 1)
     *  4      4   yes  53.000, 21.001       0.5   111 km in 1 s -> GPS jump, skipped
     *  5    200   yes  53.000, 21.002       1.0   66.92 m in 196 s
     * </pre>
     * distance = 246.5725 m; dose = (0.2 + 0.3 + 0.4 + 0.5 + 1.0 * 60) / 3600 = 0.0170556 uSv
     * (the 196 s gap counts as 60 s).
     */
    @Test
    void statisticsOfKnownRoute() throws Exception {
        send(0, "2026-10-08T10:00:00Z", true, 52.0, 21.0, 0.1);
        send(1, "2026-10-08T10:00:01Z", true, 52.0, 21.001, 0.2);
        send(2, "2026-10-08T10:00:02Z", false, 0, 0, 0.3);
        send(3, "2026-10-08T10:00:03Z", true, 52.001, 21.001, 0.4);
        send(4, "2026-10-08T10:00:04Z", true, 53.0, 21.001, 0.5);
        send(5, "2026-10-08T10:03:20Z", true, 53.0, 21.002, 1.0);
        long routeId = jdbc.sql("SELECT id FROM routes").query(Long.class).single();

        mvc.perform(get("/api/routes/{id}", routeId).with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.distanceM").value(nullValue()))
                .andExpect(jsonPath("$.preview").value(nullValue()));

        assertThat(new RouteStatsJob(statsService).runOnce()).isEqualTo(1);

        mvc.perform(get("/api/routes/{id}", routeId).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(6))
                .andExpect(jsonPath("$.fixCount").value(5))
                .andExpect(jsonPath("$.startedAt").value("2026-10-08T10:00:00Z"))
                .andExpect(jsonPath("$.endedAt").value("2026-10-08T10:03:20Z"))
                .andExpect(jsonPath("$.distanceM", closeTo(246.5725, 0.001)))
                .andExpect(jsonPath("$.doseUsv", closeTo(61.4 / 3600, 1e-9)))
                .andExpect(jsonPath("$.minUsv", closeTo(0.1, 1e-9)))
                .andExpect(jsonPath("$.avgUsv", closeTo(2.5 / 6, 1e-9)))
                .andExpect(jsonPath("$.maxUsv", closeTo(1.0, 1e-9)))
                .andExpect(jsonPath("$.preview.track", hasSize(5)))
                .andExpect(jsonPath("$.preview.track[0][0]").value(52.0))
                .andExpect(jsonPath("$.preview.track[0][1]").value(21.0))
                .andExpect(jsonPath("$.preview.track[0][2]").value(0.1))
                .andExpect(jsonPath("$.preview.track[4][1]").value(21.002))
                .andExpect(jsonPath("$.preview.spark", hasSize(6)))
                .andExpect(jsonPath("$.preview.spark[5]").value(1.0));

        assertThat(statsService.findDirty(10)).isEmpty();
        // nothing left to do
        assertThat(new RouteStatsJob(statsService).runOnce()).isZero();
    }

    @Test
    void longRouteIsReducedInPreview() {
        send(0, "2026-10-08T10:00:00Z", true, 52.0, 21.0, 0.1);
        long routeId = jdbc.sql("SELECT id FROM routes").query(Long.class).single();
        // 999 more samples: every 10th without fix, dose rate rising to 0.2 at the end
        jdbc.sql("""
                        INSERT INTO measurements (route_id, ts, seq, time_source, gps_fix, latitude, longitude,
                                                  cps, cpm, usv_per_hr, mode)
                        SELECT :routeId, TIMESTAMPTZ '2026-10-08T10:00:00Z' + g * INTERVAL '1 second', g, 'gnss',
                               g % 10 <> 0,
                               CASE WHEN g % 10 <> 0 THEN 52.0 + g * 0.00001 END,
                               CASE WHEN g % 10 <> 0 THEN 21.0 END,
                               0, 10, 0.1 + g / 9990.0, 'SLOW'
                        FROM generate_series(1, 999) g""")
                .param("routeId", routeId)
                .update();

        statsService.recompute(routeId);

        Map<String, Object> stats = jdbc.sql("""
                        SELECT sample_count, fix_count,
                               jsonb_array_length(preview -> 'track') AS track,
                               jsonb_array_length(preview -> 'spark') AS spark,
                               (preview -> 'track' -> -1 ->> 0)::float8 AS last_lat,
                               (preview -> 'spark' ->> -1)::float8 AS last_spark,
                               distance_m, stats_dirty
                        FROM routes""")
                .query().singleRow();
        assertThat(stats.get("sample_count")).isEqualTo(1000);
        assertThat(stats.get("fix_count")).isEqualTo(901);
        // 901 fixes -> every 7th (129) + the last one
        assertThat(stats.get("track")).isEqualTo(130);
        assertThat(stats.get("spark")).isEqualTo(60);
        // the last fix (seq 999) is always part of the track
        assertThat((Double) stats.get("last_lat")).isCloseTo(52.00999, within(1e-9));
        assertThat((Double) stats.get("last_spark")).isCloseTo(0.2, within(1e-9));
        // 999 * 0.00001 deg of latitude = 1110.84 m
        assertThat((Double) stats.get("distance_m")).isCloseTo(1110.84, within(0.05));
        assertThat(stats.get("stats_dirty")).isEqualTo(false);
    }

    @Test
    void routeWithoutSamplesHasNoStatistics() {
        dispatcher.dispatch("radmon/v1/esp32-1/event", "{\"type\":\"route-started\",\"r\":7,\"reason\":\"cmd\"}", NOW);
        long routeId = jdbc.sql("SELECT id FROM routes").query(Long.class).single();

        statsService.recompute(routeId);

        Map<String, Object> stats = jdbc.sql("SELECT sample_count, distance_m, dose_usv, preview FROM routes")
                .query().singleRow();
        assertThat(stats.get("sample_count")).isEqualTo(0);
        assertThat(stats.get("distance_m")).isNull();
        assertThat(stats.get("dose_usv")).isNull();
        assertThat(stats.get("preview")).isNull();
    }

    private void send(int seq, String ts, boolean fix, double lat, double lon, double usv) {
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(1, seq, ts, fix, lat, lon, usv), NOW);
    }
}
