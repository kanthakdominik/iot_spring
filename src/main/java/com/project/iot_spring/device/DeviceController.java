package com.project.iot_spring.device;

import com.project.iot_spring.ingest.ImportResult;
import com.project.iot_spring.ingest.SdImportService;
import com.project.iot_spring.web.TimeParams;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    public record DeviceUpdateRequest(@NotBlank @Size(max = 100) String name) { }

    public record CommandRequest(@NotBlank String cmd) { }

    private final DeviceService deviceService;
    private final SdImportService importService;
    private final ZoneId zone;

    public DeviceController(DeviceService deviceService, SdImportService importService,
                            @Value("${app.timezone:Europe/Warsaw}") String timezone) {
        this.deviceService = deviceService;
        this.importService = importService;
        this.zone = ZoneId.of(timezone);
    }

    @GetMapping
    public List<DeviceDto> findAll() {
        return deviceService.findAll();
    }

    @GetMapping("/{deviceId}")
    public DeviceDto find(@PathVariable String deviceId) {
        return deviceService.find(deviceId);
    }

    @PatchMapping("/{deviceId}")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceDto update(@PathVariable String deviceId, @Valid @RequestBody DeviceUpdateRequest request) {
        return deviceService.rename(deviceId, request.name());
    }

    @PostMapping("/{deviceId}/commands")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sendCommand(@PathVariable String deviceId, @Valid @RequestBody CommandRequest request,
                            Authentication authentication) {
        deviceService.sendCommand(deviceId, request.cmd(), authentication.getName());
    }

    /** Battery / GSM signal history, ascending (default: last 7 days, max 5000 points). */
    @GetMapping("/{deviceId}/health")
    public List<HealthPoint> health(@PathVariable String deviceId,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to) {
        return deviceService.health(deviceId, TimeParams.lowerBound(from, "from", zone),
                TimeParams.upperBound(to, "to", zone));
    }

    /** Import of SD card files ({@code r<N>.jsonl}): multipart field {@code files}, 1-20 files, max 50 MB. */
    @PostMapping(path = "/{deviceId}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ImportResult importFiles(@PathVariable String deviceId,
                                    @RequestPart(name = "files", required = false) List<MultipartFile> files,
                                    Authentication authentication) {
        return importService.importFiles(deviceId, files, authentication.getName());
    }
}
