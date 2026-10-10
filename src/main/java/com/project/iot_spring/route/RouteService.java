package com.project.iot_spring.route;

import com.project.iot_spring.route.RouteDtos.MarkerCreateRequest;
import com.project.iot_spring.route.RouteDtos.MarkerDto;
import com.project.iot_spring.route.RouteDtos.MeasurementDto;
import com.project.iot_spring.route.RouteDtos.RouteDto;
import com.project.iot_spring.route.RouteDtos.RouteUpdateRequest;
import com.project.iot_spring.route.RouteQueries.RouteFilter;
import com.project.iot_spring.web.NotFoundException;
import com.project.iot_spring.web.PageResponse;
import com.project.iot_spring.web.PageResponse.Paging;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RouteService {

    private final RouteRepository routeRepository;
    private final MeasurementRepository measurementRepository;
    private final RouteMarkerRepository markerRepository;
    private final RouteQueries routeQueries;
    private final RouteStatsService statsService;
    private final JdbcClient jdbc;

    @Transactional(readOnly = true)
    public PageResponse<RouteDto> findPage(RouteFilter filter, Paging paging, boolean admin) {
        return routeQueries.page(filter, paging, admin);
    }

    @Transactional(readOnly = true)
    public RouteDto find(Long routeId, boolean admin) {
        return routeQueries.find(routeId, admin)
                .orElseThrow(() -> new NotFoundException("Route not found: " + routeId));
    }

    @Transactional
    public RouteDto update(Long routeId, RouteUpdateRequest request) {
        Route route = getRoute(routeId);
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Route name cannot be empty");
            }
            route.setName(name);
        }
        if (request.description() != null) {
            String description = request.description().trim();
            route.setDescription(description.isEmpty() ? null : description);
        }
        routeRepository.saveAndFlush(route);
        return find(routeId, true);
    }

    /** Deletes the route; measurements and markers are removed by ON DELETE CASCADE. */
    @Transactional
    public void delete(Long routeId) {
        routeRepository.delete(getRoute(routeId));
    }

    /** All samples sorted by time, or only those newer than {@code after} (catching up after an SSE reconnect). */
    @Transactional(readOnly = true)
    public List<MeasurementDto> findMeasurements(Long routeId, Instant after) {
        getRoute(routeId);
        List<Measurement> measurements = after == null
                ? measurementRepository.findByRouteIdOrderByTsAscSeqAsc(routeId)
                : measurementRepository.findByRouteIdAndTsAfterOrderByTsAscSeqAsc(routeId, after);
        return measurements.stream().map(MeasurementDto::from).toList();
    }

    @Transactional
    public void deleteMeasurement(Long routeId, Long measurementId) {
        getRoute(routeId);
        if (measurementRepository.deleteFromRoute(routeId, measurementId) == 0) {
            throw new NotFoundException("Measurement " + measurementId + " not found in route " + routeId);
        }
        statsService.recompute(routeId);
    }

    @Transactional(readOnly = true)
    public List<MarkerDto> findMarkers(Long routeId) {
        getRoute(routeId);
        return markerRepository.findByRouteIdOrderByTsAsc(routeId).stream().map(MarkerDto::from).toList();
    }

    @Transactional
    public MarkerDto addMarker(Long routeId, MarkerCreateRequest request) {
        getRoute(routeId);
        if ((request.latitude() == null) != (request.longitude() == null)) {
            throw new IllegalArgumentException("Provide both latitude and longitude, or neither");
        }
        RouteMarker marker = new RouteMarker();
        marker.setRouteId(routeId);
        marker.setTs(request.timestamp() != null ? request.timestamp() : Instant.now());
        marker.setLatitude(request.latitude());
        marker.setLongitude(request.longitude());
        marker.setLabel(request.label().trim());
        marker.setSource(RouteMarker.SOURCE_USER);
        return MarkerDto.from(markerRepository.save(marker));
    }

    @Transactional
    public void deleteMarker(Long routeId, Long markerId) {
        RouteMarker marker = markerRepository.findByIdAndRouteId(markerId, routeId)
                .orElseThrow(() -> new NotFoundException("Marker " + markerId + " not found in route " + routeId));
        markerRepository.delete(marker);
    }

    // ------------------------------------------------------------------ sharing

    /** Returns the existing share token or creates a new one. */
    @Transactional
    public String share(Long routeId) {
        getRoute(routeId);
        return jdbc.sql("""
                        UPDATE routes SET share_token = COALESCE(share_token, :token)
                        WHERE id = :id
                        RETURNING share_token""")
                .param("token", ShareTokens.generate())
                .param("id", routeId)
                .query(String.class)
                .single();
    }

    @Transactional
    public void unshare(Long routeId) {
        getRoute(routeId);
        jdbc.sql("UPDATE routes SET share_token = NULL WHERE id = :id").param("id", routeId).update();
    }

    @Transactional(readOnly = true)
    public RouteDto findShared(String token) {
        return sharedRoute(token);
    }

    @Transactional(readOnly = true)
    public List<MeasurementDto> findSharedMeasurements(String token) {
        return findMeasurements(sharedRoute(token).id(), null);
    }

    @Transactional(readOnly = true)
    public List<MarkerDto> findSharedMarkers(String token) {
        return findMarkers(sharedRoute(token).id());
    }

    private RouteDto sharedRoute(String token) {
        if (!ShareTokens.isWellFormed(token)) {
            throw new NotFoundException("Shared route not found");
        }
        return routeQueries.findByShareToken(token)
                .orElseThrow(() -> new NotFoundException("Shared route not found"));
    }

    private Route getRoute(Long routeId) {
        return routeRepository.findById(routeId)
                .orElseThrow(() -> new NotFoundException("Route not found: " + routeId));
    }
}
