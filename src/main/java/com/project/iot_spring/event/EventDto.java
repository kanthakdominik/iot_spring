package com.project.iot_spring.event;

import java.time.Instant;

/** One entry of the event log (docs/V2.1-SPEC.md, section 3.4). */
public record EventDto(Long id, String deviceId, String deviceName, Long routeId, Instant ts, String type,
                       String severity, String message) {
}
