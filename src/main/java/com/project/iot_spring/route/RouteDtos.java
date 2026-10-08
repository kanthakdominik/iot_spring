package com.project.iot_spring.route;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request / response objects of the routes API (see docs/PROTOCOL.md, section 3). */
public final class RouteDtos {

    private RouteDtos() {
    }

    public record RouteDto(Long id, String deviceId, Integer deviceRouteNo, String name, String description,
                           Instant startedAt, Instant endedAt, int sampleCount) {

        public static RouteDto from(Route r) {
            return new RouteDto(r.getId(), r.getDeviceId(), r.getDeviceRouteNo(), r.getName(), r.getDescription(),
                    r.getStartedAt(), r.getEndedAt(), r.getSampleCount());
        }
    }

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
