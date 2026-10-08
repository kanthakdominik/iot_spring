package com.project.iot_spring.device;

public class MqttUnavailableException extends RuntimeException {

    public MqttUnavailableException(String message) {
        super(message);
    }
}
