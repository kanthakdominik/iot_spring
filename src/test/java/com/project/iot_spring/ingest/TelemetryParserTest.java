package com.project.iot_spring.ingest;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryParserTest {

    private static final Instant NOW = Instant.parse("2026-10-08T20:00:00Z");

    private final TelemetryParser parser = new TelemetryParser(JsonMapper.builder().build());

    private static final String VALID = """
            {"v":1,"r":42,"seq":1537,"ts":"2026-10-08T19:56:03Z","tsrc":"gnss",
             "fix":true,"lat":52.218269,"lon":21.011198,"sats":9,"hdop":0.9,"spd":4.1,
             "cps":0,"cpm":18,"usv":0.10,"mode":"SLOW"}
            """;

    @Test
    void parsesCompleteMessage() {
        Telemetry t = parser.parse(VALID, NOW);

        assertThat(t.routeNo()).isEqualTo(42);
        assertThat(t.seq()).isEqualTo(1537);
        assertThat(t.timestamp()).isEqualTo(Instant.parse("2026-10-08T19:56:03Z"));
        assertThat(t.timeSource()).isEqualTo("gnss");
        assertThat(t.gpsFix()).isTrue();
        assertThat(t.latitude()).isEqualTo(52.218269);
        assertThat(t.longitude()).isEqualTo(21.011198);
        assertThat(t.satellites()).isEqualTo((short) 9);
        assertThat(t.cpm()).isEqualTo(18);
        assertThat(t.usvPerHour()).isEqualTo(0.10);
        assertThat(t.mode()).isEqualTo("SLOW");
    }

    @Test
    void sampleWithoutFixIsAcceptedWithoutPosition() {
        Telemetry t = parser.parse("""
                {"v":1,"r":1,"seq":0,"ts":"2026-10-08T19:56:03Z","tsrc":"net","fix":false,
                 "cps":1,"cpm":20,"usv":0.11,"mode":"SLOW"}
                """, NOW);

        assertThat(t.gpsFix()).isFalse();
        assertThat(t.latitude()).isNull();
        assertThat(t.longitude()).isNull();
        assertThat(t.timeSource()).isEqualTo("net");
    }

    @Test
    void zeroZeroPositionIsTreatedAsNoFix() {
        Telemetry t = parser.parse(VALID.replace("52.218269", "0.0").replace("21.011198", "0.0"), NOW);

        assertThat(t.gpsFix()).isFalse();
        assertThat(t.latitude()).isNull();
    }

    @Test
    void missingTimestampFallsBackToServerTime() {
        Telemetry t = parser.parse(VALID.replace("\"ts\":\"2026-10-08T19:56:03Z\",", ""), NOW);

        assertThat(t.timestamp()).isEqualTo(NOW);
        assertThat(t.timeSource()).isEqualTo("srv");
    }

    @Test
    void implausibleTimestampFallsBackToServerTime() {
        Telemetry old = parser.parse(VALID.replace("2026-10-08T19:56:03Z", "1980-01-06T00:00:00Z"), NOW);
        Telemetry future = parser.parse(VALID.replace("2026-10-08T19:56:03Z", "2027-01-01T00:00:00Z"), NOW);

        assertThat(old.timeSource()).isEqualTo("srv");
        assertThat(future.timestamp()).isEqualTo(NOW);
    }

    @Test
    void rejectsUnsupportedVersion() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"v\":1", "\"v\":2"), NOW))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"lat\":52.218269", "\"lat\":95.0"), NOW))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"cps\":0", "\"cps\":-1"), NOW))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"r\":42", "\"r\":0"), NOW))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("SLOW", "TURBO"), NOW))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("mode");
    }

    @Test
    void rejectsMissingFields() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"cpm\":18,", ""), NOW))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("cpm");
    }

    @Test
    void rejectsFixWithoutPosition() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"lat\":52.218269,", ""), NOW))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("lat");
    }

    @Test
    void rejectsMalformedJsonAndLegacyCsv() {
        assertThatThrownBy(() -> parser.parse("{not json", NOW)).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> parser.parse(
                "76, 51.791870, 19.404711, 2025-05-04 16:45:10, CPS, 1, CPM, 28, uSv/hr, 0.15, SLOW", NOW))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void ignoresUnknownFields() {
        Telemetry t = parser.parse(VALID.replace("\"v\":1,", "\"v\":1,\"extra\":\"x\","), NOW);

        assertThat(t.routeNo()).isEqualTo(42);
    }
}
