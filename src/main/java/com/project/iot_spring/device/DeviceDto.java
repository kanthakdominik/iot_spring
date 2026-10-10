package com.project.iot_spring.device;

import java.time.Instant;

/** Device with status (docs/V2.1-SPEC.md, section 3.3). */
public record DeviceDto(
        String id,
        String name,
        boolean online,
        Instant lastSeenAt,
        String fwVersion,
        Integer currentRouteNo,
        Long currentRouteId,
        String lastReset,
        Integer batteryMv,
        Integer batteryPct,
        Instant batteryAt,
        Integer signalCsq,
        Instant signalAt,
        long routeCount) {
}
