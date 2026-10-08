package com.project.iot_spring.device;

import com.project.iot_spring.web.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DeviceService {

    /** Commands understood by the firmware (see docs/PROTOCOL.md, section 2.4). */
    public static final Set<String> COMMANDS = Set.of("new-route", "battery");

    private final DeviceRepository deviceRepository;
    private final ObjectProvider<DeviceCommandSender> commandSender;

    @Transactional(readOnly = true)
    public List<DeviceDto> findAll() {
        return deviceRepository.findAllByOrderByIdAsc().stream().map(DeviceDto::from).toList();
    }

    @Transactional
    public DeviceDto rename(String deviceId, String name) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new NotFoundException("Device not found: " + deviceId));
        device.setName(name.trim());
        return DeviceDto.from(device);
    }

    public void sendCommand(String deviceId, String command) {
        if (!COMMANDS.contains(command)) {
            throw new IllegalArgumentException("Unknown command: " + command + ". Allowed: " + COMMANDS);
        }
        if (!deviceRepository.existsById(deviceId)) {
            throw new NotFoundException("Device not found: " + deviceId);
        }
        DeviceCommandSender sender = commandSender.getIfAvailable();
        if (sender == null) {
            throw new MqttUnavailableException("MQTT is not enabled on this server");
        }
        sender.sendCommand(deviceId, command);
    }
}
