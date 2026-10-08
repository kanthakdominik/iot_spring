package com.project.iot_spring.route;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RouteMarkerRepository extends JpaRepository<RouteMarker, Long> {

    List<RouteMarker> findByRouteIdOrderByTsAsc(Long routeId);

    Optional<RouteMarker> findByIdAndRouteId(Long id, Long routeId);
}
