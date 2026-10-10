package com.project.iot_spring.alert;

import java.time.Instant;

/**
 * Facts published by ingest (Spring application events) that may raise an alert. They are evaluated by
 * {@link AlertService} after the ingest transaction commits, on its own thread.
 */
public sealed interface AlertTrigger {

    String deviceId();

    Instant ts();

    /** A stored sample (only live MQTT samples, not SD imports). */
    record DoseRate(String deviceId, Long routeId, Instant ts, double usv, Double latitude, Double longitude)
            implements AlertTrigger { }

    /** A battery reading from a status message or a battery event. */
    record Battery(String deviceId, Instant ts, int pct) implements AlertTrigger { }

    /** The device went offline (Last Will). */
    record Offline(String deviceId, Instant ts) implements AlertTrigger { }
}
