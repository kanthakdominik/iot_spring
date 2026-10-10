package com.project.iot_spring.stats;

import com.project.iot_spring.route.RouteDtos.RouteDto;

import java.time.Instant;
import java.util.List;

/** Responses of /api/stats, /api/map and /api/search (docs/V2.1-SPEC.md, section 3.5). */
public final class StatsDtos {

    private StatsDtos() {
    }

    public record Summary(long routeCount, long deviceCount, long devicesOnline, long sampleCount, double distanceM,
                          double doseUsv, MaxUsv maxUsv, long openAlerts, RouteDto lastRoute) { }

    public record MaxUsv(double value, long routeId, String routeName, Instant ts) { }

    /** One day with data; {@code date} in the application time zone (yyyy-MM-dd). */
    public record ActivityDay(String date, long samples, long routes, double maxUsv) { }

    /** {@code lat/lon} = south-west corner of the cell. */
    public record GridCell(double lat, double lon, double avgUsv, double maxUsv, long count) { }

    public record Grid(double cellDeg, List<GridCell> cells) { }

    public record SearchResult(List<RouteHit> routes, List<DeviceHit> devices, List<MarkerHit> markers) { }

    public record RouteHit(long id, String name, String deviceId, Instant startedAt) { }

    public record DeviceHit(String id, String name) { }

    public record MarkerHit(long id, long routeId, String label, Instant ts) { }
}
