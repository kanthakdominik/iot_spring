package com.project.iot_spring.device;

import com.project.iot_spring.common.Db;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read side of devices: {@link DeviceDto} with the current route id and the number of routes. */
@Component
public class DeviceQueries {

    private static final String SELECT = """
            SELECT d.id, d.name, d.online, d.last_seen_at, d.fw_version, d.current_route_no, cr.id AS current_route_id,
                   d.last_reset, d.battery_mv, d.battery_pct, d.battery_at, d.signal_csq, d.signal_at,
                   (SELECT count(*) FROM routes r WHERE r.device_id = d.id) AS route_count
            FROM devices d
            LEFT JOIN routes cr ON cr.device_id = d.id AND cr.device_route_no = d.current_route_no
            """;

    private static final RowMapper<DeviceDto> MAPPER = (rs, n) -> new DeviceDto(
            rs.getString("id"),
            rs.getString("name"),
            rs.getBoolean("online"),
            Db.instant(rs, "last_seen_at"),
            rs.getString("fw_version"),
            Db.integer(rs, "current_route_no"),
            Db.longValue(rs, "current_route_id"),
            rs.getString("last_reset"),
            Db.integer(rs, "battery_mv"),
            Db.integer(rs, "battery_pct"),
            Db.instant(rs, "battery_at"),
            Db.integer(rs, "signal_csq"),
            Db.instant(rs, "signal_at"),
            rs.getLong("route_count"));

    private static final RowMapper<HealthPoint> HEALTH_MAPPER = (rs, n) -> new HealthPoint(
            Db.instant(rs, "ts"),
            Db.integer(rs, "battery_mv"),
            Db.integer(rs, "battery_pct"),
            Db.integer(rs, "signal_csq"));

    private final JdbcClient jdbc;

    public DeviceQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DeviceDto> findAll() {
        return jdbc.sql(SELECT + " ORDER BY d.id").query(MAPPER).list();
    }

    public Optional<DeviceDto> find(String deviceId) {
        return jdbc.sql(SELECT + " WHERE d.id = :id").param("id", deviceId).query(MAPPER).optional();
    }

    public boolean exists(String deviceId) {
        return jdbc.sql("SELECT count(*) FROM devices WHERE id = :id").param("id", deviceId)
                .query(Long.class).single() > 0;
    }

    /** The newest {@code limit} readings in [from, to), returned in ascending time order. */
    public List<HealthPoint> health(String deviceId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT ts, battery_mv, battery_pct, signal_csq FROM (
                            SELECT ts, battery_mv, battery_pct, signal_csq
                            FROM device_health
                            WHERE device_id = :id AND ts >= :from AND ts < :to
                            ORDER BY ts DESC
                            LIMIT :limit
                        ) h ORDER BY ts""")
                .param("id", deviceId)
                .param("from", Db.utc(from))
                .param("to", Db.utc(to))
                .param("limit", limit)
                .query(HEALTH_MAPPER)
                .list();
    }
}
