package com.project.iot_spring.ingest;

import java.util.List;

/**
 * Result of an SD card import (docs/V2.1-SPEC.md, section 3.3).
 *
 * @param files      number of uploaded files
 * @param lines      non-empty lines
 * @param imported   samples stored
 * @param duplicates valid samples that were already stored (e.g. received live over MQTT)
 * @param invalid    lines that are not a valid telemetry frame v1 with a plausible {@code ts}
 */
public record ImportResult(int files, int lines, int imported, int duplicates, int invalid, List<RouteImport> routes) {

    public record RouteImport(long routeId, int deviceRouteNo, int imported) { }
}
