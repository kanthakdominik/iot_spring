package com.project.iot_spring.route;

import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request / response objects of the routes API (see docs/V2.1-SPEC.md, section 3.2). */
public final class RouteDtos {

    private RouteDtos() {
    }

    /**
     * Route with statistics (docs/V2.1-SPEC.md, section 3.2).
     *
     * @param preview    {@code {"track":[[lat,lon,usv],...],"spark":[usv,...]}} - stored as JSONB and passed through
     *                   as raw JSON; null until RouteStatsJob computes it
     * @param shareToken only for ADMIN, otherwise null
     */
    public record RouteDto(Long id, String deviceId, String deviceName, Integer deviceRouteNo, String name,
                           String description, Instant startedAt, Instant endedAt, int sampleCount, int fixCount,
                           Double distanceM, Double minUsv, Double avgUsv, Double maxUsv, Double doseUsv,
                           boolean active, @JsonRawValue String preview, String shareToken) {

        public RouteDto withoutPreview() {
            return new RouteDto(id, deviceId, deviceName, deviceRouteNo, name, description, startedAt, endedAt,
                    sampleCount, fixCount, distanceM, minUsv, avgUsv, maxUsv, doseUsv, active, null, shareToken);
        }

        public RouteDto withoutShareToken() {
            return new RouteDto(id, deviceId, deviceName, deviceRouteNo, name, description, startedAt, endedAt,
                    sampleCount, fixCount, distanceM, minUsv, avgUsv, maxUsv, doseUsv, active, preview, null);
        }
    }

    /** SSE {@code measurement}: a stored live sample. */
    public record MeasurementEvent(Long routeId, String deviceId, MeasurementDto measurement) { }

    public record ShareTokenDto(String token) { }

    /** PATCH body: null = leave unchanged, empty description = remove it. */
    public record RouteUpdateRequest(
            @Size(min = 1, max = 100) String name,
            @Size(max = 2000) String description) { }

    public record MeasurementDto(Long id, int seq, Instant timestamp, String timeSource, boolean gpsFix,
                                 Double latitude, Double longitude, Short satellites, Float hdop, Float speed,
                                 int cps, int cpm, double usvPerHour, String mode) {

        public static MeasurementDto from(Measurement m) {
            return new MeasurementDto(m.getId(), m.getSeq(), m.getTs(), m.getTimeSource(), m.isGpsFix(),
                    m.getLatitude(), m.getLongitude(), m.getSatellites(), m.getHdop(), m.getSpeed(),
                    m.getCps(), m.getCpm(), m.getUsvPerHour(), m.getMode());
        }
    }

    public record MarkerDto(Long id, Instant timestamp, Double latitude, Double longitude, String label,
                            String source) {

        public static MarkerDto from(RouteMarker m) {
            return new MarkerDto(m.getId(), m.getTs(), m.getLatitude(), m.getLongitude(), m.getLabel(),
                    m.getSource());
        }
    }

    public record MarkerCreateRequest(
            @NotBlank @Size(max = 200) String label,
            Instant timestamp,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude) { }
}
