package com.project.iot_spring.route;

import com.project.iot_spring.live.LiveEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Route statistics (docs/V2.1-SPEC.md, section 2.1), recomputed from scratch with one SQL statement:
 * <ul>
 *   <li>{@code sample_count}, {@code fix_count}, {@code started_at}, {@code ended_at}</li>
 *   <li>{@code min/avg/max_usv} over all samples</li>
 *   <li>{@code dose_usv} = sum of {@code usv_i * min(ts_i - ts_(i-1), 60 s) / 3600} (first sample = 0)</li>
 *   <li>{@code distance_m} = sum of haversine distances between consecutive samples with a GPS fix,
 *       without segments faster than 100 m/s (GPS jumps); NULL with fewer than 2 fixes</li>
 *   <li>{@code preview.track}: at most 150 fixes (every k-th + the last one) as {@code [lat, lon, usv]}</li>
 *   <li>{@code preview.spark}: up to 60 values, max usv in each of ntile(60) time buckets</li>
 * </ul>
 * The route row is locked first (SELECT ... FOR UPDATE), so a sample stored concurrently either is part of
 * the computation or marks the route dirty again after it.
 */
@Slf4j
@Service
public class RouteStatsService {

    static final String RECOMPUTE_SQL = """
            WITH m AS (
                SELECT ts, seq, gps_fix, latitude, longitude, usv_per_hr AS usv,
                       lag(ts) OVER (ORDER BY ts, seq)    AS prev_ts,
                       ntile(60) OVER (ORDER BY ts, seq)  AS bucket
                FROM measurements
                WHERE route_id = :routeId
            ),
            f AS (
                SELECT ts, latitude AS lat, longitude AS lon, usv,
                       lag(ts) OVER w        AS prev_ts,
                       lag(latitude) OVER w  AS prev_lat,
                       lag(longitude) OVER w AS prev_lon,
                       row_number() OVER w   AS rn,
                       count(*) OVER ()      AS cnt
                FROM m
                WHERE gps_fix
                WINDOW w AS (ORDER BY ts, seq)
            ),
            seg AS (
                SELECT 2 * 6371008.8 * asin(LEAST(1.0, sqrt(
                           power(sin(radians(lat - prev_lat) / 2), 2)
                           + cos(radians(prev_lat)) * cos(radians(lat)) * power(sin(radians(lon - prev_lon) / 2), 2)))) AS d,
                       extract(epoch FROM ts - prev_ts)::float8 AS dt
                FROM f
                WHERE prev_lat IS NOT NULL
            ),
            stats AS (
                SELECT count(*)                           AS n,
                       count(*) FILTER (WHERE gps_fix)    AS fixes,
                       min(ts)                            AS t0,
                       max(ts)                            AS t1,
                       min(usv)                           AS min_usv,
                       avg(usv)                           AS avg_usv,
                       max(usv)                           AS max_usv,
                       sum(usv * LEAST(COALESCE(extract(epoch FROM ts - prev_ts)::float8, 0), 60)) / 3600.0 AS dose
                FROM m
            ),
            dist AS (
                SELECT CASE WHEN count(*) = 0 THEN NULL
                            ELSE COALESCE(sum(d) FILTER (WHERE d <= 100 * GREATEST(dt, 1)), 0) END AS distance
                FROM seg
            ),
            track AS (
                SELECT json_agg(json_build_array(round(lat::numeric, 5)::float8, round(lon::numeric, 5)::float8,
                                                 round(usv::numeric, 3)::float8) ORDER BY rn) AS track
                FROM f
                WHERE cnt <= 150 OR (rn - 1) % ceil((cnt - 1) / 148.0)::bigint = 0 OR rn = cnt
            ),
            spark AS (
                SELECT json_agg(round(v::numeric, 3)::float8 ORDER BY bucket) AS spark
                FROM (SELECT bucket, max(usv) AS v FROM m GROUP BY bucket) b
            )
            UPDATE routes r SET
                sample_count = s.n,
                fix_count    = s.fixes,
                started_at   = s.t0,
                ended_at     = s.t1,
                min_usv      = s.min_usv,
                avg_usv      = s.avg_usv,
                max_usv      = s.max_usv,
                dose_usv     = s.dose,
                distance_m   = dist.distance,
                preview      = CASE WHEN s.n = 0 THEN NULL
                                    ELSE jsonb_build_object('track', COALESCE(track.track, '[]'::json),
                                                            'spark', COALESCE(spark.spark, '[]'::json)) END,
                stats_dirty  = false
            FROM stats s, dist, track, spark
            WHERE r.id = :routeId
            """;

    private final JdbcClient jdbc;
    private final RouteQueries routeQueries;
    private final ApplicationEventPublisher publisher;

    public RouteStatsService(JdbcClient jdbc, RouteQueries routeQueries, ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.routeQueries = routeQueries;
        this.publisher = publisher;
    }

    /** Ids of routes waiting for recomputation, oldest id first. */
    @Transactional(readOnly = true)
    public List<Long> findDirty(int limit) {
        return jdbc.sql("SELECT id FROM routes WHERE stats_dirty ORDER BY id LIMIT :limit")
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    /**
     * Recomputes the statistics of one route and sends SSE {@code route} (without preview) after commit.
     *
     * @return false when the route does not exist
     */
    @Transactional
    public boolean recompute(long routeId) {
        boolean exists = !jdbc.sql("SELECT id FROM routes WHERE id = :routeId FOR UPDATE")
                .param("routeId", routeId)
                .query(Long.class)
                .list()
                .isEmpty();
        if (!exists) {
            return false;
        }
        jdbc.sql(RECOMPUTE_SQL).param("routeId", routeId).update();
        routeQueries.find(routeId, false)
                .ifPresent(route -> publisher.publishEvent(LiveEvent.route(route.withoutPreview())));
        return true;
    }
}
