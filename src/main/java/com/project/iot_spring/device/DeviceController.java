package com.project.iot_spring.device;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    public record DeviceUpdateRequest(@NotBlank @Size(max = 100) String name) { }

    public record CommandRequest(@NotBlank String cmd) { }

    private final DeviceService deviceService;

    @GetMapping
    public List<DeviceDto> findAll() {
        return deviceService.findAll();
    }

    @PatchMapping("/{deviceId}")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceDto update(@PathVariable String deviceId, @Valid @RequestBody DeviceUpdateRequest request) {
        return deviceService.rename(deviceId, request.name());
    }

    @PostMapping("/{deviceId}/commands")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sendCommand(@PathVariable String deviceId, @Valid @RequestBody CommandRequest request) {
        deviceService.sendCommand(deviceId, request.cmd());
    }
}
