package com.project.iot_spring.device;

import java.time.Instant;

public record DeviceDto(
        String id,
        String name,
        boolean online,
        Instant lastSeenAt,
        String fwVersion,
        Integer currentRouteNo,
        String lastReset,
        Integer batteryMv,
        Integer batteryPct,
        Instant batteryAt) {

    public static DeviceDto from(Device d) {
        return new DeviceDto(d.getId(), d.getName(), d.isOnline(), d.getLastSeenAt(), d.getFwVersion(),
                d.getCurrentRouteNo(), d.getLastReset(), d.getBatteryMv(), d.getBatteryPct(), d.getBatteryAt());
    }
}
