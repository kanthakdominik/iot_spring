package com.project.iot_spring.alert;

import java.time.Instant;

public record AlertDto(Long id, Long ruleId, String ruleName, String type, String deviceId, String deviceName,
                       Long routeId, Instant ts, Double value, Double threshold, Double latitude, Double longitude,
                       String message, Instant acknowledgedAt, String acknowledgedBy) {
}
