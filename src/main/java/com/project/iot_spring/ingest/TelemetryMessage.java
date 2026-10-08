package com.project.iot_spring.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Raw telemetry payload v1 as sent by the device (docs/PROTOCOL.md, section 2.1). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryMessage(
        Integer v,
        Integer r,
        Integer seq,
        String ts,
        String tsrc,
        Boolean fix,
        Double lat,
        Double lon,
        Integer sats,
        Double hdop,
        Double spd,
        Integer cps,
        Integer cpm,
        Double usv,
        String mode) {
}
