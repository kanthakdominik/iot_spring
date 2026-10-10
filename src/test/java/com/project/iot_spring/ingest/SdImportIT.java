package com.project.iot_spring.ingest;

import com.project.iot_spring.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/devices/{id}/import - files r<N>.jsonl from the SD card. */
class SdImportIT extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MqttMessageDispatcher dispatcher;

    @BeforeEach
    void deviceSentOneSampleLive() {
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(5, 0, "2026-10-08T10:00:00Z", true),
                Instant.parse("2026-10-08T10:00:01Z"));
    }

    @Test
    void importCountsImportedDuplicateAndInvalidLines() throws Exception {
        String r5 = String.join("\n",
                sample(5, 0, "2026-10-08T10:00:00Z", true),          // already received live -> duplicate
                sample(5, 1, "2026-10-08T10:00:01Z", true),
                sample(5, 2, "2026-10-08T10:00:02Z", false),
                "",
                sample(5, 3, "2026-10-08T10:00:03Z", true).replace("\"ts\":\"2026-10-08T10:00:03Z\",", ""), // no ts
                sample(5, 4, "1980-01-06T00:00:04Z", true),          // clock never set
                "garbage") + "\n";
        String r6 = "﻿" + sample(6, 0, "2026-10-09T08:00:00Z", true) + "\r\n"
                + sample(6, 1, "2026-10-09T08:00:01Z", true) + "\r\n";

        mvc.perform(multipart("/api/devices/{id}/import", "esp32-1")
                        .file(file("r5.jsonl", r5))
                        .file(file("r6.JSONL", r6))
                        .with(csrf()).with(user(ADMIN_USER).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files").value(2))
                .andExpect(jsonPath("$.lines").value(8))
                .andExpect(jsonPath("$.imported").value(4))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.invalid").value(3))
                .andExpect(jsonPath("$.routes", hasSize(2)))
                .andExpect(jsonPath("$.routes[0].deviceRouteNo").value(5))
                .andExpect(jsonPath("$.routes[0].imported").value(2))
                .andExpect(jsonPath("$.routes[1].deviceRouteNo").value(6))
                .andExpect(jsonPath("$.routes[1].imported").value(2))
                .andExpect(jsonPath("$.routes[1].routeId").isNumber());

        // statistics recomputed right away
        Map<String, Object> r5Stats = jdbc.sql("""
                        SELECT sample_count, fix_count, stats_dirty FROM routes WHERE device_route_no = 5""")
                .query().singleRow();
        assertThat(r5Stats.get("sample_count")).isEqualTo(3);
        assertThat(r5Stats.get("fix_count")).isEqualTo(2);
        assertThat(r5Stats.get("stats_dirty")).isEqualTo(false);
        assertThat(jdbc.sql("SELECT count(*) FROM device_events WHERE type = 'IMPORT'").query(Long.class).single())
                .isEqualTo(1);

        // importing the same files again changes nothing
        mvc.perform(multipart("/api/devices/{id}/import", "esp32-1")
                        .file(file("r5.jsonl", r5))
                        .file(file("r6.jsonl", r6))
                        .with(csrf()).with(user(ADMIN_USER).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.duplicates").value(5))
                .andExpect(jsonPath("$.invalid").value(3));
        assertThat(count("measurements")).isEqualTo(5);
    }

    @Test
    void importedSamplesDoNotRaiseAlerts() throws Exception {
        mvc.perform(multipart("/api/devices/{id}/import", "esp32-1")
                        .file(file("r7.jsonl", sample(7, 0, "2026-10-08T11:00:00Z", true, 52.2, 21.0, 5.0)))
                        .with(csrf()).with(user(ADMIN_USER).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
        awaitAlerts();
        assertThat(count("alerts")).isZero();
    }

    @Test
    void invalidUploadsAreRejected() throws Exception {
        mvc.perform(multipart("/api/devices/{id}/import", "esp32-1")
                        .file(file("photo.png", "x"))
                        .with(csrf()).with(user(ADMIN_USER).roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/devices/{id}/import", "esp32-1")
                        .with(csrf()).with(user(ADMIN_USER).roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/devices/{id}/import", "unknown")
                        .file(file("r1.jsonl", "x"))
                        .with(csrf()).with(user(ADMIN_USER).roles("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(multipart("/api/devices/{id}/import", "esp32-1")
                        .file(file("r1.jsonl", "x"))
                        .with(csrf()).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("files", name, "application/octet-stream",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
