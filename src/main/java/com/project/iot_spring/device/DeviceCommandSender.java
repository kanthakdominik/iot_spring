package com.project.iot_spring.device;

/**
 * Sends a command to a device ("radmon/v1/{deviceId}/cmd").
 * Implemented by the MQTT gateway; absent when MQTT is disabled (tests, local development).
 */
public interface DeviceCommandSender {

    void sendCommand(String deviceId, String command);
}
