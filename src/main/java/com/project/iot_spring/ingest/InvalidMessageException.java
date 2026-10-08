package com.project.iot_spring.ingest;

/** An MQTT message that does not follow docs/PROTOCOL.md. The message is logged and dropped. */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }
}
