package com.project.iot_spring.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Parses date/time request parameters. Accepted: an ISO instant ({@code 2026-10-01T00:00:00Z}),
 * a date-time with offset ({@code 2026-10-01T02:00:00+02:00}) or a plain date ({@code 2026-10-01},
 * interpreted in the application time zone; as an upper bound it means the end of that day).
 */
public final class TimeParams {

    private TimeParams() {
    }

    public static Instant lowerBound(String value, String name, ZoneId zone) {
        return parse(value, name, zone, false);
    }

    public static Instant upperBound(String value, String name, ZoneId zone) {
        return parse(value, name, zone, true);
    }

    private static Instant parse(String value, String name, ZoneId zone, boolean upper) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
            // try the other formats
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
            // try a plain date
        }
        try {
            LocalDate date = LocalDate.parse(v);
            return (upper ? date.plusDays(1) : date).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date/time in parameter '" + name + "': " + value);
        }
    }
}
