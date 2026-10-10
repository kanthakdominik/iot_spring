package com.project.iot_spring.route;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MeasurementRepository extends JpaRepository<Measurement, Long> {

    List<Measurement> findByRouteIdOrderByTsAscSeqAsc(Long routeId);

    List<Measurement> findByRouteIdAndTsAfterOrderByTsAscSeqAsc(Long routeId, Instant after);

    @Modifying
    @Query(value = "DELETE FROM measurements WHERE id = :id AND route_id = :routeId", nativeQuery = true)
    int deleteFromRoute(@Param("routeId") Long routeId, @Param("id") Long id);
}
