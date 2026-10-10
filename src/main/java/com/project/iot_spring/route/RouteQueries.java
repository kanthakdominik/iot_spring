package com.project.iot_spring.route;

import com.project.iot_spring.common.Db;
import com.project.iot_spring.route.RouteDtos.RouteDto;
import com.project.iot_spring.web.PageResponse;
import com.project.iot_spring.web.PageResponse.Paging;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read side of routes: {@link RouteDto} with device name, statistics, preview and the "active" flag. */
@Component
public class RouteQueries {

    /** API sort keys -> SQL columns (whitelist, nothing from the request goes into SQL directly). */
    static final Map<String, String> SORT_COLUMNS = Map.of(
            "startedAt", "r.started_at",
            "distance", "r.distance_m",
            "maxUsv", "r.max_usv",
            "samples", "r.sample_count",
            "name", "lower(r.name)");

    /** Filters of {@code GET /api/routes}; null = no filter. */
    public record RouteFilter(String q, String deviceId, Instant from, Instant to, String sort, String dir) { }

    private static final String SELECT = """
            SELECT r.id, r.device_id, d.name AS device_name, r.device_route_no, r.name, r.description,
                   r.started_at, r.ended_at, r.sample_count, r.fix_count, r.distance_m,
                   r.min_usv, r.avg_usv, r.max_usv, r.dose_usv,
                   COALESCE(d.online AND d.current_route_no = r.device_route_no
                            AND r.ended_at > now() - INTERVAL '5 minutes', false) AS active,
                   r.preview::text AS preview, r.share_token
            FROM routes r JOIN devices d ON d.id = r.device_id
            """;

    private static final RowMapper<RouteDto> MAPPER = (rs, n) -> new RouteDto(
            rs.getLong("id"),
            rs.getString("device_id"),
            rs.getString("device_name"),
            rs.getInt("device_route_no"),
            rs.getString("name"),
            rs.getString("description"),
            Db.instant(rs, "started_at"),
            Db.instant(rs, "ended_at"),
            rs.getInt("sample_count"),
            rs.getInt("fix_count"),
            Db.doubleValue(rs, "distance_m"),
            Db.doubleValue(rs, "min_usv"),
            Db.doubleValue(rs, "avg_usv"),
            Db.doubleValue(rs, "max_usv"),
            Db.doubleValue(rs, "dose_usv"),
            rs.getBoolean("active"),
            rs.getString("preview"),
            rs.getString("share_token"));

    private final JdbcClient jdbc;

    public RouteQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RouteDto> find(long routeId, boolean withShareToken) {
        return jdbc.sql(SELECT + " WHERE r.id = :id")
                .param("id", routeId)
                .query(MAPPER)
                .optional()
                .map(r -> withShareToken ? r : r.withoutShareToken());
    }

    public Optional<RouteDto> findByShareToken(String token) {
        return jdbc.sql(SELECT + " WHERE r.share_token = :token")
                .param("token", token)
                .query(MAPPER)
                .optional()
                .map(RouteDto::withoutShareToken);
    }

    /** Newest route by start time (routes without samples last). */
    public Optional<RouteDto> latest(boolean withShareToken) {
        return jdbc.sql(SELECT + " ORDER BY r.started_at DESC NULLS LAST, r.id DESC LIMIT 1")
                .query(MAPPER)
                .optional()
                .map(r -> withShareToken ? r : r.withoutShareToken());
    }

    public PageResponse<RouteDto> page(RouteFilter filter, Paging paging, boolean withShareToken) {
        String sortColumn = filter.sort() == null || filter.sort().isBlank()
                ? SORT_COLUMNS.get("startedAt")
                : SORT_COLUMNS.get(filter.sort());
        if (sortColumn == null) {
            throw new IllegalArgumentException("Invalid sort: " + filter.sort() + ". Allowed: " + SORT_COLUMNS.keySet());
        }
        String dir = filter.dir() == null || filter.dir().isBlank() ? "desc" : filter.dir().toLowerCase();
        if (!dir.equals("asc") && !dir.equals("desc")) {
            throw new IllegalArgumentException("Invalid dir: " + filter.dir() + ". Allowed: asc, desc");
        }

        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (filter.q() != null && !filter.q().isBlank()) {
            where.add("""
                    (r.name ILIKE :q ESCAPE '\\' OR r.description ILIKE :q ESCAPE '\\'
                     OR r.id::text ILIKE :q ESCAPE '\\' OR d.name ILIKE :q ESCAPE '\\')""");
            params.put("q", Db.containsPattern(filter.q().trim()));
        }
        if (filter.deviceId() != null && !filter.deviceId().isBlank()) {
            where.add("r.device_id = :deviceId");
            params.put("deviceId", filter.deviceId());
        }
        if (filter.from() != null) {
            where.add("r.started_at >= :from");
            params.put("from", Db.utc(filter.from()));
        }
        if (filter.to() != null) {
            where.add("r.started_at < :to");
            params.put("to", Db.utc(filter.to()));
        }
        String whereSql = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);

        long total = jdbc.sql("SELECT count(*) FROM routes r JOIN devices d ON d.id = r.device_id" + whereSql)
                .params(params)
                .query(Long.class)
                .single();
        List<RouteDto> content = jdbc.sql(SELECT + whereSql
                        + " ORDER BY " + sortColumn + " " + dir + " NULLS LAST, r.id " + dir
                        + " LIMIT :limit OFFSET :offset")
                .params(params)
                .param("limit", paging.size())
                .param("offset", paging.offset())
                .query(MAPPER)
                .list()
                .stream()
                .map(r -> withShareToken ? r : r.withoutShareToken())
                .toList();
        return PageResponse.of(content, paging, total);
    }
}
