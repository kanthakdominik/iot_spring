package com.project.iot_spring.event;

import java.util.Set;

/** Values of {@code device_events.type} and {@code device_events.severity}. */
public final class EventTypes {

    public static final String DEVICE_ONLINE = "DEVICE_ONLINE";
    public static final String DEVICE_OFFLINE = "DEVICE_OFFLINE";
    public static final String ROUTE_STARTED = "ROUTE_STARTED";
    public static final String BATTERY = "BATTERY";
    public static final String DEVICE_ERROR = "DEVICE_ERROR";
    public static final String MARKER = "MARKER";
    public static final String COMMAND_SENT = "COMMAND_SENT";
    public static final String ALERT = "ALERT";
    public static final String IMPORT = "IMPORT";

    public static final Set<String> ALL = Set.of(DEVICE_ONLINE, DEVICE_OFFLINE, ROUTE_STARTED, BATTERY, DEVICE_ERROR,
            MARKER, COMMAND_SENT, ALERT, IMPORT);

    public static final String INFO = "INFO";
    public static final String WARN = "WARN";
    public static final String ERROR = "ERROR";

    private EventTypes() {
    }
}
