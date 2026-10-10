package com.project.iot_spring.stats;

import com.project.iot_spring.common.Db;
import com.project.iot_spring.route.RouteQueries;
import com.project.iot_spring.stats.StatsDtos.ActivityDay;
import com.project.iot_spring.stats.StatsDtos.DeviceHit;
import com.project.iot_spring.stats.StatsDtos.Grid;
import com.project.iot_spring.stats.StatsDtos.GridCell;
import com.project.iot_spring.stats.StatsDtos.MarkerHit;
import com.project.iot_spring.stats.StatsDtos.MaxUsv;
import com.project.iot_spring.stats.StatsDtos.RouteHit;
import com.project.iot_spring.stats.StatsDtos.SearchResult;
import com.project.iot_spring.stats.StatsDtos.Summary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard statistics, the aggregated map and the global search (docs/V2.1-SPEC.md, section 3.5). */
@Service
public class StatsService {

    static final Duration DEFAULT_ACTIVITY_RANGE = Duration.ofDays(365);
    static final int MIN_ZOOM = 3;
    static final int MAX_ZOOM = 18;
    static final int MAX_CELLS = 10_000;

    private final JdbcClient jdbc;
    private final RouteQueries routeQueries;
    private final ZoneId zone;

    public StatsService(JdbcClient jdbc, RouteQueries routeQueries,
                        @Value("${app.timezone:Europe/Warsaw}") String timezone) {
        this.jdbc = jdbc;
        this.routeQueries = routeQueries;
        this.zone = ZoneId.of(timezone);
    }

    public ZoneId zone() {
        return zone;
    }

    // ------------------------------------------------------------------ summary

    /** Totals use the per-route statistics (RouteStatsJob), so they lag behind live data by up to 30 s. */
    @Transactional(readOnly = true)
    public Summary summary(boolean admin) {
        Map<String, Object> totals = jdbc.sql("""
                        SELECT (SELECT count(*) FROM routes)                                   AS route_count,
                               (SELECT count(*) FROM devices)                                  AS device_count,
                               (SELECT count(*) FROM devices WHERE online)                     AS devices_online,
                               (SELECT COALESCE(sum(sample_count), 0) FROM routes)             AS sample_count,
                               (SELECT COALESCE(sum(distance_m), 0) FROM routes)               AS distance_m,
                               (SELECT COALESCE(sum(dose_usv), 0) FROM routes)                 AS dose_usv,
                               (SELECT count(*) FROM alerts WHERE acknowledged_at IS NULL)     AS open_alerts
                        """)
                .query()
                .singleRow();

        MaxUsv maxUsv = jdbc.sql("""
                        SELECT r.id, r.name, r.max_usv,
                               (SELECT m.ts FROM measurements m WHERE m.route_id = r.id
                                ORDER BY m.usv_per_hr DESC, m.ts LIMIT 1) AS ts
                        FROM routes r
                        WHERE r.max_usv IS NOT NULL
                        ORDER BY r.max_usv DESC, r.id
                        LIMIT 1""")
                .query((rs, n) -> new MaxUsv(rs.getDouble("max_usv"), rs.getLong("id"), rs.getString("name"),
                        Db.instant(rs, "ts")))
                .optional()
                .orElse(null);

        return new Summary(
                number(totals.get("route_count")).longValue(),
                number(totals.get("device_count")).longValue(),
                number(totals.get("devices_online")).longValue(),
                number(totals.get("sample_count")).longValue(),
                number(totals.get("distance_m")).doubleValue(),
                number(totals.get("dose_usv")).doubleValue(),
                maxUsv,
                number(totals.get("open_alerts")).longValue(),
                routeQueries.latest(admin).orElse(null));
    }

    // ------------------------------------------------------------------ activity calendar

    /** Samples, routes and max dose rate per day (application time zone), only days with data. */
    @Transactional(readOnly = true)
    public List<ActivityDay> activity(Instant from, Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(DEFAULT_ACTIVITY_RANGE);
        return jdbc.sql("""
                        SELECT (m.ts AT TIME ZONE :tz)::date AS day,
                               count(*)                      AS samples,
                               count(DISTINCT m.route_id)    AS routes,
                               max(m.usv_per_hr)             AS max_usv
                        FROM measurements m
                        WHERE m.ts >= :from AND m.ts < :to
                        GROUP BY 1
                        ORDER BY 1""")
                .param("tz", zone.getId())
                .param("from", Db.utc(start))
                .param("to", Db.utc(end))
                .query((rs, n) -> new ActivityDay(rs.getString("day"), rs.getLong("samples"), rs.getLong("routes"),
                        rs.getDouble("max_usv")))
                .list();
    }

    // ------------------------------------------------------------------ aggregated map

    /** Cell size in degrees for a map zoom level: 360 / 2^zoom / 16 (zoom clamped to 3..18). */
    static double cellDeg(int zoom) {
        int z = Math.clamp(zoom, MIN_ZOOM, MAX_ZOOM);
        return 360.0 / (1L << z) / 16.0;
    }

    /**
     * Samples with a GPS fix inside the bounding box, aggregated into square cells (at most 10 000 cells,
     * the ones with the most samples). {@code west > east} means a box crossing the 180th meridian.
     */
    @Transactional(readOnly = true)
    public Grid grid(double south, double west, double north, double east, int zoom,
                     Instant from, Instant to, String deviceId) {
        if (south > north) {
            throw new IllegalArgumentException("'south' must not be greater than 'north'");
        }
        if (south < -90 || north > 90 || west < -180 || west > 180 || east < -180 || east > 180) {
            throw new IllegalArgumentException("Bounding box out of range");
        }
        double cell = cellDeg(zoom);

        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        where.add("m.gps_fix");
        where.add("m.latitude BETWEEN :south AND :north");
        where.add(west <= east ? "m.longitude BETWEEN :west AND :east" : "(m.longitude >= :west OR m.longitude <= :east)");
        params.put("south", south);
        params.put("north", north);
        params.put("west", west);
        params.put("east", east);
        if (from != null) {
            where.add("m.ts >= :from");
            params.put("from", Db.utc(from));
        }
        if (to != null) {
            where.add("m.ts < :to");
            params.put("to", Db.utc(to));
        }
        String join = "";
        if (deviceId != null && !deviceId.isBlank()) {
            join = " JOIN routes r ON r.id = m.route_id";
            where.add("r.device_id = :deviceId");
            params.put("deviceId", deviceId);
        }

        List<GridCell> cells = jdbc.sql("""
                        SELECT gy * :cell AS lat, gx * :cell AS lon, avg_usv, max_usv, cnt
                        FROM (
                            SELECT floor(m.latitude / :cell)  AS gy,
                                   floor(m.longitude / :cell) AS gx,
                                   avg(m.usv_per_hr)          AS avg_usv,
                                   max(m.usv_per_hr)          AS max_usv,
                                   count(*)                   AS cnt
                            FROM measurements m""" + join + " WHERE " + String.join(" AND ", where) + """

                            GROUP BY 1, 2
                            ORDER BY cnt DESC, 1, 2
                            LIMIT :limit
                        ) c""")
                .params(params)
                .param("cell", cell)
                .param("limit", MAX_CELLS)
                .query((rs, n) -> new GridCell(rs.getDouble("lat"), rs.getDouble("lon"), rs.getDouble("avg_usv"),
                        rs.getDouble("max_usv"), rs.getLong("cnt")))
                .list();
        return new Grid(cell, cells);
    }

    // ------------------------------------------------------------------ search

    /** Routes (max 8), devices (max 5) and markers (max 5) matching {@code q} (at least 2 characters). */
    @Transactional(readOnly = true)
    public SearchResult search(String q) {
        String text = q == null ? "" : q.trim();
        if (text.length() < 2) {
            return new SearchResult(List.of(), List.of(), List.of());
        }
        String pattern = Db.containsPattern(text);
        List<RouteHit> routes = jdbc.sql("""
                        SELECT r.id, r.name, r.device_id, r.started_at
                        FROM routes r
                        WHERE r.name ILIKE :q ESCAPE '\\' OR r.description ILIKE :q ESCAPE '\\'
                           OR r.id::text = :exact
                        ORDER BY r.started_at DESC NULLS LAST, r.id DESC
                        LIMIT 8""")
                .param("q", pattern)
                .param("exact", text)
                .query((rs, n) -> new RouteHit(rs.getLong("id"), rs.getString("name"), rs.getString("device_id"),
                        Db.instant(rs, "started_at")))
                .list();
        List<DeviceHit> devices = jdbc.sql("""
                        SELECT id, name FROM devices
                        WHERE id ILIKE :q ESCAPE '\\' OR name ILIKE :q ESCAPE '\\'
                        ORDER BY id
                        LIMIT 5""")
                .param("q", pattern)
                .query((rs, n) -> new DeviceHit(rs.getString("id"), rs.getString("name")))
                .list();
        List<MarkerHit> markers = jdbc.sql("""
                        SELECT id, route_id, label, ts FROM route_markers
                        WHERE label ILIKE :q ESCAPE '\\'
                        ORDER BY ts DESC, id DESC
                        LIMIT 5""")
                .param("q", pattern)
                .query((rs, n) -> new MarkerHit(rs.getLong("id"), rs.getLong("route_id"), rs.getString("label"),
                        Db.instant(rs, "ts")))
                .list();
        return new SearchResult(routes, devices, markers);
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : 0;
    }
}
