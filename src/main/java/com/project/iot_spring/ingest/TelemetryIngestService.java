package com.project.iot_spring.ingest;

import com.project.iot_spring.alert.AlertTrigger;
import com.project.iot_spring.common.Db;
import com.project.iot_spring.device.DeviceQueries;
import com.project.iot_spring.event.EventService;
import com.project.iot_spring.event.EventTypes;
import com.project.iot_spring.live.LiveEvent;
import com.project.iot_spring.route.RouteDtos.MeasurementDto;
import com.project.iot_spring.route.RouteDtos.MeasurementEvent;
import com.project.iot_spring.route.RouteQueries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores telemetry samples.
 * <ul>
 *   <li>the device row is created on first contact (only devices with a certificate signed by our CA can
 *       connect, and the broker ACL lets each device publish only under its own id)</li>
 *   <li>the route is found or created for the pair (deviceId, routeNo) - the server owns the route id and
 *       never overwrites a name given by the user</li>
 *   <li>a sample with an already stored (route, seq, ts) is ignored, so a device may safely resend data</li>
 *   <li>a stored sample marks the route {@code stats_dirty} (RouteStatsJob recomputes the statistics), is sent
 *       to the live stream and checked against the alert rules - both after commit</li>
 * </ul>
 * Plain SQL (JdbcClient) is used on purpose: INSERT ... ON CONFLICT is the simplest way to get idempotent
 * writes into the TimescaleDB hypertable.
 */
@Slf4j
@Service
public class TelemetryIngestService {

    static final String INSERT_MEASUREMENT = """
            INSERT INTO measurements (route_id, ts, seq, time_source, gps_fix, latitude, longitude,
                                      satellites, hdop, speed, cps, cpm, usv_per_hr, mode)
            VALUES (:routeId, :ts, :seq, :timeSource, :gpsFix, :lat, :lon,
                    :sats, :hdop, :speed, :cps, :cpm, :usv, :mode)
            ON CONFLICT (route_id, seq, ts) DO NOTHING
            """;

    static final int BATCH_SIZE = 1000;

    /** Result of {@link #touchDevice}: the device state before the update. */
    record DeviceTouch(Boolean wasOnline, Integer previousRouteNo, Integer routeNo) {

        boolean isNew() {
            return wasOnline == null;
        }

        /** New device, came back online or switched to another route. */
        boolean changed() {
            return wasOnline == null || !wasOnline || !Objects.equals(previousRouteNo, routeNo);
        }
    }

    /** Route found or created for (deviceId, routeNo). */
    public record RouteRef(long id, boolean created) { }

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final EventService eventService;
    private final RouteQueries routeQueries;
    private final DeviceQueries deviceQueries;
    private final ApplicationEventPublisher publisher;

    public TelemetryIngestService(JdbcClient jdbc, NamedParameterJdbcTemplate namedJdbc, EventService eventService,
                                  RouteQueries routeQueries, DeviceQueries deviceQueries,
                                  ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.eventService = eventService;
        this.routeQueries = routeQueries;
        this.deviceQueries = deviceQueries;
        this.publisher = publisher;
    }

    /** Live sample from MQTT. @return true when the sample was stored, false when it was a duplicate */
    @Transactional
    public boolean ingest(String deviceId, Telemetry t, Instant receivedAt) {
        DeviceTouch touch = touchDevice(deviceId, t.routeNo(), receivedAt);
        RouteRef route = findOrCreateRoute(deviceId, t.routeNo(), null);
        if (touch.changed() || route.created()) {
            publishDevice(deviceId);
        }

        Optional<Long> id = jdbc.sql(INSERT_MEASUREMENT + " RETURNING id")
                .paramSource(measurementParams(route.id(), t))
                .query(Long.class)
                .optional();
        if (id.isEmpty()) {
            log.debug("Duplicate sample ignored: device={} r={} seq={}", deviceId, t.routeNo(), t.seq());
            return false;
        }

        jdbc.sql("""
                        UPDATE routes SET
                            sample_count = sample_count + 1,
                            fix_count    = fix_count + :fix,
                            started_at   = LEAST(started_at, :ts),
                            ended_at     = GREATEST(ended_at, :ts),
                            stats_dirty  = true
                        WHERE id = :routeId
                        """)
                .param("fix", t.gpsFix() ? 1 : 0)
                .param("ts", Db.utc(t.timestamp()))
                .param("routeId", route.id())
                .update();

        MeasurementDto measurement = toDto(id.get(), t);
        publisher.publishEvent(LiveEvent.measurement(new MeasurementEvent(route.id(), deviceId, measurement)));
        publisher.publishEvent(new AlertTrigger.DoseRate(deviceId, route.id(), t.timestamp(), t.usvPerHour(),
                t.latitude(), t.longitude()));
        return true;
    }

    /**
     * Inserts samples of one route in batches (SD card import). Duplicates are skipped by ON CONFLICT.
     * Route counters and statistics are not touched - the caller recomputes them.
     *
     * @return number of inserted samples
     */
    @Transactional
    public int insertBatch(long routeId, List<Telemetry> samples) {
        int inserted = 0;
        for (int from = 0; from < samples.size(); from += BATCH_SIZE) {
            List<Telemetry> chunk = samples.subList(from, Math.min(samples.size(), from + BATCH_SIZE));
            SqlParameterSource[] params = chunk.stream()
                    .map(t -> measurementParams(routeId, t))
                    .toArray(SqlParameterSource[]::new);
            for (int count : namedJdbc.batchUpdate(INSERT_MEASUREMENT, params)) {
                if (count > 0 || count == Statement.SUCCESS_NO_INFO) {
                    inserted++;
                }
            }
        }
        return inserted;
    }

    /** Creates the device on first contact and marks it as online / active on the given route. */
    DeviceTouch touchDevice(String deviceId, Integer currentRouteNo, Instant seenAt) {
        return jdbc.sql("""
                        WITH old AS (SELECT online, current_route_no FROM devices WHERE id = :id)
                        INSERT INTO devices (id, name, last_seen_at, online, current_route_no)
                        VALUES (:id, :id, :seenAt, true, :routeNo)
                        ON CONFLICT (id) DO UPDATE SET
                            last_seen_at     = EXCLUDED.last_seen_at,
                            online           = true,
                            current_route_no = COALESCE(EXCLUDED.current_route_no, devices.current_route_no)
                        RETURNING (SELECT online FROM old)           AS was_online,
                                  (SELECT current_route_no FROM old) AS previous_route_no,
                                  current_route_no
                        """)
                .param("id", deviceId)
                .param("seenAt", Db.utc(seenAt))
                .param("routeNo", currentRouteNo, Types.INTEGER)
                .query((rs, n) -> new DeviceTouch(
                        (Boolean) rs.getObject("was_online"),
                        Db.integer(rs, "previous_route_no"),
                        Db.integer(rs, "current_route_no")))
                .single();
    }

    /**
     * @param reason why the route started (written to the ROUTE_STARTED event when the route is new), may be null
     * @return database id of the route identified by (deviceId, routeNo), created when missing
     */
    public RouteRef findOrCreateRoute(String deviceId, int routeNo, String reason) {
        List<Long> created = jdbc.sql("""
                        INSERT INTO routes (device_id, device_route_no, name)
                        VALUES (:deviceId, :routeNo, :name)
                        ON CONFLICT (device_id, device_route_no) DO NOTHING
                        RETURNING id
                        """)
                .param("deviceId", deviceId)
                .param("routeNo", routeNo)
                .param("name", defaultRouteName(deviceId, routeNo))
                .query(Long.class)
                .list();
        if (!created.isEmpty()) {
            long routeId = created.getFirst();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("r", routeNo);
            if (reason != null) {
                data.put("reason", reason);
            }
            eventService.log(deviceId, routeId, EventTypes.ROUTE_STARTED, EventTypes.INFO,
                    "Route #" + routeNo + " started" + (reason != null ? " (" + reason + ")" : ""), data);
            routeQueries.find(routeId, false)
                    .ifPresent(r -> publisher.publishEvent(LiveEvent.route(r.withoutPreview())));
            return new RouteRef(routeId, true);
        }

        long routeId = jdbc.sql("SELECT id FROM routes WHERE device_id = :deviceId AND device_route_no = :routeNo")
                .param("deviceId", deviceId)
                .param("routeNo", routeNo)
                .query(Long.class)
                .single();
        return new RouteRef(routeId, false);
    }

    /** Sends the current device state to the live stream (after commit). */
    void publishDevice(String deviceId) {
        deviceQueries.find(deviceId).ifPresent(d -> publisher.publishEvent(LiveEvent.device(d)));
    }

    private static MapSqlParameterSource measurementParams(long routeId, Telemetry t) {
        return new MapSqlParameterSource()
                .addValue("routeId", routeId)
                .addValue("ts", Db.utc(t.timestamp()))
                .addValue("seq", t.seq())
                .addValue("timeSource", t.timeSource())
                .addValue("gpsFix", t.gpsFix())
                .addValue("lat", t.latitude(), Types.DOUBLE)
                .addValue("lon", t.longitude(), Types.DOUBLE)
                .addValue("sats", t.satellites(), Types.SMALLINT)
                .addValue("hdop", t.hdop(), Types.REAL)
                .addValue("speed", t.speed(), Types.REAL)
                .addValue("cps", t.cps())
                .addValue("cpm", t.cpm())
                .addValue("usv", t.usvPerHour())
                .addValue("mode", t.mode());
    }

    static MeasurementDto toDto(long id, Telemetry t) {
        return new MeasurementDto(id, t.seq(), t.timestamp(), t.timeSource(), t.gpsFix(), t.latitude(),
                t.longitude(), t.satellites(), t.hdop(), t.speed(), t.cps(), t.cpm(), t.usvPerHour(), t.mode());
    }

    static String defaultRouteName(String deviceId, int routeNo) {
        return deviceId + " #" + routeNo;
    }
}
