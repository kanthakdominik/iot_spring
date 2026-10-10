package com.project.iot_spring.ingest;

import com.project.iot_spring.device.DeviceQueries;
import com.project.iot_spring.event.EventService;
import com.project.iot_spring.event.EventTypes;
import com.project.iot_spring.ingest.ImportResult.RouteImport;
import com.project.iot_spring.ingest.TelemetryIngestService.RouteRef;
import com.project.iot_spring.route.RouteStatsService;
import com.project.iot_spring.web.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Import of the {@code r<N>.jsonl} files written by the ESP32 to its SD card (docs/V2.1-SPEC.md, section 3.3).
 * <ul>
 *   <li>every line is a telemetry frame v1 (the same JSON as on MQTT); a line without a valid, plausible
 *       {@code ts} is invalid - there is no meaningful server time for old data</li>
 *   <li>samples are stored with the same {@code INSERT ... ON CONFLICT DO NOTHING} as live data, so lines
 *       already received over MQTT (or imported before) count as duplicates</li>
 *   <li>statistics of the affected routes are recomputed right away, an {@code IMPORT} event is written</li>
 *   <li>imported samples are not sent to the live stream and are not checked against alert rules
 *       (historical data must not trigger push notifications)</li>
 * </ul>
 */
@Slf4j
@Service
public class SdImportService {

    public static final int MAX_FILES = 20;
    public static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;

    private final TelemetryParser parser;
    private final TelemetryIngestService ingestService;
    private final RouteStatsService statsService;
    private final DeviceQueries deviceQueries;
    private final EventService eventService;
    private final TransactionTemplate transactionTemplate;

    public SdImportService(TelemetryParser parser, TelemetryIngestService ingestService,
                           RouteStatsService statsService, DeviceQueries deviceQueries, EventService eventService,
                           TransactionTemplate transactionTemplate) {
        this.parser = parser;
        this.ingestService = ingestService;
        this.statsService = statsService;
        this.deviceQueries = deviceQueries;
        this.eventService = eventService;
        this.transactionTemplate = transactionTemplate;
    }

    public ImportResult importFiles(String deviceId, List<MultipartFile> files, String username) {
        if (!deviceQueries.exists(deviceId)) {
            throw new NotFoundException("Device not found: " + deviceId);
        }
        validate(files);

        Instant now = Instant.now();
        Map<Integer, List<Telemetry>> byRoute = new TreeMap<>();
        int lines = 0;
        int invalid = 0;
        for (MultipartFile file : files) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String text = line.replace("﻿", "").trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    lines++;
                    try {
                        Telemetry t = parser.parseWithDeviceTime(text, now);
                        byRoute.computeIfAbsent(t.routeNo(), r -> new ArrayList<>()).add(t);
                    } catch (InvalidMessageException e) {
                        invalid++;
                    }
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read file " + file.getOriginalFilename() + ": "
                        + e.getMessage());
            }
        }
        int valid = lines - invalid;
        int fileCount = files.size();
        int lineCount = lines;
        int invalidCount = invalid;

        ImportResult result = transactionTemplate.execute(status -> {
            List<RouteImport> routes = new ArrayList<>();
            int imported = 0;
            for (Map.Entry<Integer, List<Telemetry>> entry : byRoute.entrySet()) {
                RouteRef route = ingestService.findOrCreateRoute(deviceId, entry.getKey(), "import");
                int count = ingestService.insertBatch(route.id(), entry.getValue());
                statsService.recompute(route.id());
                routes.add(new RouteImport(route.id(), entry.getKey(), count));
                imported += count;
            }
            ImportResult r = new ImportResult(fileCount, lineCount, imported, valid - imported, invalidCount, routes);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("files", r.files());
            data.put("lines", r.lines());
            data.put("imported", r.imported());
            data.put("duplicates", r.duplicates());
            data.put("invalid", r.invalid());
            data.put("routes", routes.stream().map(RouteImport::routeId).toList());
            data.put("user", username);
            eventService.log(deviceId, routes.size() == 1 ? routes.getFirst().routeId() : null, EventTypes.IMPORT,
                    EventTypes.INFO, String.format(Locale.ROOT,
                            "SD import by %s: %d samples imported, %d duplicates, %d invalid lines (%d files)",
                            username, r.imported(), r.duplicates(), r.invalid(), r.files()), data);
            return r;
        });
        log.info("SD import for {}: {}", deviceId, result);
        return result;
    }

    static void validate(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files uploaded (multipart field 'files')");
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("Too many files: " + files.size() + " (max " + MAX_FILES + ")");
        }
        long total = 0;
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jsonl") && !name.endsWith(".txt")) {
                throw new IllegalArgumentException("Only .jsonl and .txt files are accepted: "
                        + file.getOriginalFilename());
            }
            total += file.getSize();
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("Files are too large together (max 50 MB)");
        }
    }
}
