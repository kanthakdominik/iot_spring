package com.project.iot_spring.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeParamsTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @Test
    void parsesInstantsOffsetsAndDates() {
        assertThat(TimeParams.lowerBound("2026-10-01T10:00:00Z", "from", WARSAW))
                .isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(TimeParams.lowerBound("2026-10-01T12:00:00+02:00", "from", WARSAW))
                .isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        // plain date = local midnight; as an upper bound the end of that day
        assertThat(TimeParams.lowerBound("2026-10-01", "from", WARSAW))
                .isEqualTo(Instant.parse("2026-09-30T22:00:00Z"));
        assertThat(TimeParams.upperBound("2026-10-01", "to", WARSAW))
                .isEqualTo(Instant.parse("2026-10-01T22:00:00Z"));
        assertThat(TimeParams.upperBound("2026-12-01", "to", WARSAW))
                .isEqualTo(Instant.parse("2026-12-01T23:00:00Z"));
    }

    @Test
    void emptyMeansNoBound() {
        assertThat(TimeParams.lowerBound(null, "from", WARSAW)).isNull();
        assertThat(TimeParams.upperBound(" ", "to", WARSAW)).isNull();
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> TimeParams.lowerBound("yesterday", "from", WARSAW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }
}
