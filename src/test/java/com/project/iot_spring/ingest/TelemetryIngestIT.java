package com.project.iot_spring.ingest;

import com.project.iot_spring.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryIngestIT extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-08T20:00:00Z");

    @Autowired
    private MqttMessageDispatcher dispatcher;

    @Test
    void firstSampleCreatesDeviceAndRoute() {
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(1, 0, "2026-10-08T19:00:00Z", true), NOW);

        Map<String, Object> route = jdbc.sql("SELECT * FROM routes").query().singleRow();
        assertThat(route.get("device_id")).isEqualTo("esp32-1");
        assertThat(route.get("device_route_no")).isEqualTo(1);
        assertThat(route.get("name")).isEqualTo("esp32-1 #1");
        assertThat(route.get("sample_count")).isEqualTo(1);
        assertThat(count("devices")).isEqualTo(1);
        assertThat(count("measurements")).isEqualTo(1);
    }

    @Test
    void duplicateSampleIsIgnored() {
        String payload = sample(1, 5, "2026-10-08T19:00:05Z", true);
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", payload, NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", payload, NOW);

        assertThat(count("measurements")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT sample_count FROM routes").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void userGivenRouteNameIsNeverOverwritten() {
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(7, 0, "2026-10-08T19:00:00Z", true), NOW);
        jdbc.sql("UPDATE routes SET name = 'Las Kabacki'").update();

        // e.g. after a backend restart the device keeps sending samples of the same route
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(7, 1, "2026-10-08T19:00:01Z", true), NOW);

        assertThat(jdbc.sql("SELECT name FROM routes").query(String.class).single()).isEqualTo("Las Kabacki");
        assertThat(count("routes")).isEqualTo(1);
    }

    @Test
    void sameLocalRouteNumberOnTwoDevicesGivesTwoRoutes() {
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(3, 0, "2026-10-08T19:00:00Z", true), NOW);
        dispatcher.dispatch("radmon/v1/esp32-2/telemetry", sample(3, 0, "2026-10-08T19:00:00Z", true), NOW);

        assertThat(count("routes")).isEqualTo(2);
        assertThat(count("devices")).isEqualTo(2);
    }

    @Test
    void sampleWithoutGpsFixIsStoredWithoutPosition() {
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(1, 0, "2026-10-08T19:00:00Z", false), NOW);

        Map<String, Object> m = jdbc.sql("SELECT gps_fix, latitude, longitude FROM measurements").query().singleRow();
        assertThat(m.get("gps_fix")).isEqualTo(false);
        assertThat(m.get("latitude")).isNull();
        assertThat(m.get("longitude")).isNull();
    }

    @Test
    void routeTimeRangeFollowsSamples() {
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(1, 1, "2026-10-08T19:00:10Z", true), NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(1, 0, "2026-10-08T19:00:00Z", true), NOW);
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", sample(1, 2, "2026-10-08T19:00:20Z", true), NOW);

        Map<String, Object> route = jdbc.sql("""
                SELECT sample_count,
                       to_char(started_at AT TIME ZONE 'UTC', 'HH24:MI:SS') AS started,
                       to_char(ended_at AT TIME ZONE 'UTC', 'HH24:MI:SS')   AS ended
                FROM routes""").query().singleRow();
        assertThat(route.get("sample_count")).isEqualTo(3);
        assertThat(route.get("started")).isEqualTo("19:00:00");
        assertThat(route.get("ended")).isEqualTo("19:00:20");
    }

    @Test
    void invalidMessagesAreDroppedWithoutException() {
        dispatcher.dispatch("radmon/v1/esp32-1/telemetry", "garbage", NOW);
        dispatcher.dispatch("esp32/data", "76, 51.79, 19.40, 2025-05-04 16:45:10, CPS, 1, CPM, 28, uSv/hr, 0.15, SLOW", NOW);

        assertThat(count("measurements")).isZero();
    }

    @Test
    void statusAndLastWillUpdateDevice() {
        dispatcher.dispatch("radmon/v1/esp32-1/status",
                "{\"online\":true,\"fw\":\"2.0.0\",\"r\":12,\"reset\":\"POWERON\",\"bat_mv\":3980,\"bat_pct\":70}", NOW);

        Map<String, Object> device = jdbc.sql("SELECT * FROM devices").query().singleRow();
        assertThat(device.get("online")).isEqualTo(true);
        assertThat(device.get("fw_version")).isEqualTo("2.0.0");
        assertThat(device.get("current_route_no")).isEqualTo(12);
        assertThat(device.get("battery_mv")).isEqualTo(3980);

        dispatcher.dispatch("radmon/v1/esp32-1/status", "{\"online\":false}", NOW);

        assertThat(jdbc.sql("SELECT online FROM devices").query(Boolean.class).single()).isFalse();
    }

    @Test
    void markerEventCreatesRouteMarker() {
        dispatcher.dispatch("radmon/v1/esp32-1/event",
                "{\"type\":\"marker\",\"r\":4,\"seq\":10,\"ts\":\"2026-10-08T19:30:00Z\",\"fix\":true,"
                        + "\"lat\":52.21,\"lon\":21.01,\"label\":\"button\"}", NOW);

        Map<String, Object> marker = jdbc.sql("SELECT * FROM route_markers").query().singleRow();
        assertThat(marker.get("label")).isEqualTo("button");
        assertThat(marker.get("source")).isEqualTo("device");
        assertThat(count("routes")).isEqualTo(1);
    }

    @Test
    void batteryEventUpdatesDevice() {
        dispatcher.dispatch("radmon/v1/esp32-1/event", "{\"type\":\"battery\",\"mv\":3700,\"pct\":40}", NOW);

        Map<String, Object> device = jdbc.sql("SELECT battery_mv, battery_pct FROM devices").query().singleRow();
        assertThat(device.get("battery_mv")).isEqualTo(3700);
        assertThat(device.get("battery_pct")).isEqualTo(40);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    static String sample(int routeNo, int seq, String ts, boolean fix) {
        String position = fix ? "\"fix\":true,\"lat\":52.218269,\"lon\":21.011198," : "\"fix\":false,";
        return "{\"v\":1,\"r\":" + routeNo + ",\"seq\":" + seq + ",\"ts\":\"" + ts + "\",\"tsrc\":\"gnss\","
                + position + "\"cps\":0,\"cpm\":18,\"usv\":0.10,\"mode\":\"SLOW\"}";
    }
}
