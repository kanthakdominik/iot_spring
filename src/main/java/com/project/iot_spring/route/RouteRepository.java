package com.project.iot_spring.route;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RouteRepository extends JpaRepository<Route, Long> {

    @Query("select r from Route r order by r.startedAt desc nulls last, r.id desc")
    List<Route> findAllNewestFirst();

    @Query("select r from Route r where r.deviceId = :deviceId order by r.startedAt desc nulls last, r.id desc")
    List<Route> findByDeviceNewestFirst(@Param("deviceId") String deviceId);

    Optional<Route> findByDeviceIdAndDeviceRouteNo(String deviceId, Integer deviceRouteNo);

    /** Recomputes sample count and time range after measurements were deleted. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE routes SET
                sample_count = (SELECT count(*) FROM measurements m WHERE m.route_id = :routeId),
                started_at   = (SELECT min(m.ts) FROM measurements m WHERE m.route_id = :routeId),
                ended_at     = (SELECT max(m.ts) FROM measurements m WHERE m.route_id = :routeId)
            WHERE id = :routeId
            """, nativeQuery = true)
    void refreshStatistics(@Param("routeId") Long routeId);
}
