package com.project.iot_spring.web;

/** The request conflicts with the current state (e.g. username taken, last administrator) - HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
