package com.project.iot_spring.alert;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alert rule; also the body of POST / PUT (the {@code id} is ignored there, missing booleans default to
 * {@code true}, missing cooldown to 15 minutes).
 */
public record AlertRuleDto(
        Long id,
        @NotBlank @Size(max = 100) String name,
        @NotBlank String type,
        Double threshold,
        @Size(max = 32) String deviceId,
        Boolean enabled,
        @Min(0) @Max(10_080) Integer cooldownMinutes,
        Boolean notifyPush) {

    public static final String DOSE_RATE_ABOVE = "DOSE_RATE_ABOVE";
    public static final String BATTERY_BELOW = "BATTERY_BELOW";
    public static final String DEVICE_OFFLINE = "DEVICE_OFFLINE";

    public boolean appliesTo(String device) {
        return deviceId == null || deviceId.equals(device);
    }
}
