package com.project.iot_spring.device;

import com.project.iot_spring.event.EventService;
import com.project.iot_spring.event.EventTypes;
import com.project.iot_spring.web.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DeviceService {

    /** Commands understood by the firmware (see docs/PROTOCOL.md, section 2.4). */
    public static final Set<String> COMMANDS = Set.of("new-route", "battery");

    static final Duration DEFAULT_HEALTH_RANGE = Duration.ofDays(7);
    static final int MAX_HEALTH_POINTS = 5000;

    private final DeviceQueries deviceQueries;
    private final JdbcClient jdbc;
    private final EventService eventService;
    private final ObjectProvider<DeviceCommandSender> commandSender;

    @Transactional(readOnly = true)
    public List<DeviceDto> findAll() {
        return deviceQueries.findAll();
    }

    @Transactional(readOnly = true)
    public DeviceDto find(String deviceId) {
        return deviceQueries.find(deviceId)
                .orElseThrow(() -> new NotFoundException("Device not found: " + deviceId));
    }

    @Transactional
    public DeviceDto rename(String deviceId, String name) {
        int updated = jdbc.sql("UPDATE devices SET name = :name WHERE id = :id")
                .param("name", name.trim())
                .param("id", deviceId)
                .update();
        if (updated == 0) {
            throw new NotFoundException("Device not found: " + deviceId);
        }
        return find(deviceId);
    }

    /** Battery / signal history, ascending; default range = last 7 days, at most 5000 (newest) points. */
    @Transactional(readOnly = true)
    public List<HealthPoint> health(String deviceId, Instant from, Instant to) {
        if (!deviceQueries.exists(deviceId)) {
            throw new NotFoundException("Device not found: " + deviceId);
        }
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(DEFAULT_HEALTH_RANGE);
        return deviceQueries.health(deviceId, start, end, MAX_HEALTH_POINTS);
    }

    public void sendCommand(String deviceId, String command, String username) {
        if (!COMMANDS.contains(command)) {
            throw new IllegalArgumentException("Unknown command: " + command + ". Allowed: " + COMMANDS);
        }
        if (!deviceQueries.exists(deviceId)) {
            throw new NotFoundException("Device not found: " + deviceId);
        }
        DeviceCommandSender sender = commandSender.getIfAvailable();
        if (sender == null) {
            throw new MqttUnavailableException("MQTT is not enabled on this server");
        }
        sender.sendCommand(deviceId, command);
        eventService.log(deviceId, null, EventTypes.COMMAND_SENT, EventTypes.INFO,
                "Command '" + command + "' sent by " + username, Map.of("cmd", command, "user", username));
    }
}
