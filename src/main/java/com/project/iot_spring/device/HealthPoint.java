package com.project.iot_spring.device;

import java.time.Instant;

/** One battery / GSM signal reading ({@code device_health}). */
public record HealthPoint(Instant ts, Integer batteryMv, Integer batteryPct, Integer signalCsq) {
}
