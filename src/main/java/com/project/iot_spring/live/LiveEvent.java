package com.project.iot_spring.live;

/**
 * A message for the browsers connected to {@code GET /api/stream} (docs/V2.1-SPEC.md, section 3.6).
 * Domain code publishes it with {@code ApplicationEventPublisher}; {@link LiveEventBroadcaster} sends it
 * after the surrounding transaction commits.
 *
 * @param name SSE event name ({@code event:} line)
 * @param data object serialized to JSON ({@code data:} line)
 */
public record LiveEvent(String name, Object data) {

    public static final String MEASUREMENT = "measurement";
    public static final String ROUTE = "route";
    public static final String DEVICE = "device";
    public static final String ALERT = "alert";
    public static final String EVENT = "event";

    public static LiveEvent measurement(Object data) {
        return new LiveEvent(MEASUREMENT, data);
    }

    public static LiveEvent route(Object data) {
        return new LiveEvent(ROUTE, data);
    }

    public static LiveEvent device(Object data) {
        return new LiveEvent(DEVICE, data);
    }

    public static LiveEvent alert(Object data) {
        return new LiveEvent(ALERT, data);
    }

    public static LiveEvent event(Object data) {
        return new LiveEvent(EVENT, data);
    }
}
