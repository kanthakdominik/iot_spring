package com.project.iot_spring.ingest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Routes an incoming MQTT message to the right handler based on its topic.
 * Independent of the MQTT library, so it can be tested without a broker.
 * Never throws: a bad message is logged and dropped (an exception would break the MQTT client callback).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqttMessageDispatcher {

    private final TelemetryParser telemetryParser;
    private final TelemetryIngestService ingestService;
    private final DeviceEventService deviceEventService;

    public void dispatch(String topic, String payload, Instant receivedAt) {
        Optional<MqttTopic> parsed = MqttTopic.parse(topic);
        if (parsed.isEmpty()) {
            log.warn("Ignoring message on unexpected topic [{}]", topic);
            return;
        }
        String deviceId = parsed.get().deviceId();
        try {
            switch (parsed.get().kind()) {
                case TELEMETRY -> ingestService.ingest(deviceId, telemetryParser.parse(payload, receivedAt), receivedAt);
                case STATUS -> deviceEventService.handleStatus(deviceId, payload, receivedAt);
                case EVENT -> deviceEventService.handleEvent(deviceId, payload, receivedAt);
                case CMD -> { /* our own commands - nothing to do */ }
            }
        } catch (InvalidMessageException e) {
            log.warn("Rejected message from {} on [{}]: {} | payload: {}", deviceId, topic, e.getMessage(),
                    abbreviate(payload));
        } catch (RuntimeException e) {
            log.error("Failed to process message from {} on [{}] | payload: {}", deviceId, topic,
                    abbreviate(payload), e);
        }
    }

    private static String abbreviate(String payload) {
        if (payload == null) {
            return "null";
        }
        return payload.length() <= 300 ? payload : payload.substring(0, 300) + "...";
    }
}
