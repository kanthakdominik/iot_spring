package com.project.iot_spring.web;

/** Thrown when a requested resource (route, device, measurement, marker) does not exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
