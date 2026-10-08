package com.project.iot_spring.ingest;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Topic structure: {@code radmon/v1/{deviceId}/{kind}}.
 */
public record MqttTopic(String deviceId, Kind kind) {

    public static final String PREFIX = "radmon/v1/";
    public static final Pattern DEVICE_ID = Pattern.compile("^[a-z0-9-]{1,32}$");

    private static final Pattern TOPIC = Pattern.compile("^radmon/v1/([^/]+)/(telemetry|status|event|cmd)$");

    public enum Kind {
        TELEMETRY, STATUS, EVENT, CMD
    }

    /** Topic filters the backend subscribes to. */
    public static final String[] BACKEND_SUBSCRIPTIONS = {
            PREFIX + "+/telemetry",
            PREFIX + "+/status",
            PREFIX + "+/event"
    };

    public static Optional<MqttTopic> parse(String topic) {
        if (topic == null) {
            return Optional.empty();
        }
        Matcher m = TOPIC.matcher(topic);
        if (!m.matches() || !DEVICE_ID.matcher(m.group(1)).matches()) {
            return Optional.empty();
        }
        return Optional.of(new MqttTopic(m.group(1), Kind.valueOf(m.group(2).toUpperCase())));
    }

    public static String commandTopic(String deviceId) {
        return PREFIX + deviceId + "/cmd";
    }
}
