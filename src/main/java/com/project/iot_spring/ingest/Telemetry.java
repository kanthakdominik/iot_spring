package com.project.iot_spring.ingest;

import java.time.Instant;

/** A validated telemetry sample, ready to be stored. */
public record Telemetry(
        int routeNo,
        int seq,
        Instant timestamp,
        String timeSource,
        boolean gpsFix,
        Double latitude,
        Double longitude,
        Short satellites,
        Float hdop,
        Float speed,
        int cps,
        int cpm,
        double usvPerHour,
        String mode) {

    public static final String TIME_GNSS = "gnss";
    public static final String TIME_NETWORK = "net";
    public static final String TIME_SERVER = "srv";
}
