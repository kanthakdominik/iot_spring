package com.project.iot_spring.route;

import com.project.iot_spring.route.RouteDtos.MarkerCreateRequest;
import com.project.iot_spring.route.RouteDtos.MarkerDto;
import com.project.iot_spring.route.RouteDtos.MeasurementDto;
import com.project.iot_spring.route.RouteDtos.RouteDto;
import com.project.iot_spring.route.RouteDtos.RouteUpdateRequest;
import com.project.iot_spring.web.NotFoundException;
import lombok.RequiredArgsConstructor;
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

    @Transactional(readOnly = true)
    public List<RouteDto> findAll(String deviceId) {
        List<Route> routes = (deviceId == null || deviceId.isBlank())
                ? routeRepository.findAllNewestFirst()
                : routeRepository.findByDeviceNewestFirst(deviceId);
        return routes.stream().map(RouteDto::from).toList();
    }

    @Transactional(readOnly = true)
    public RouteDto find(Long routeId) {
        return RouteDto.from(getRoute(routeId));
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
        return RouteDto.from(route);
    }

    /** Deletes the route; measurements and markers are removed by ON DELETE CASCADE. */
    @Transactional
    public void delete(Long routeId) {
        routeRepository.delete(getRoute(routeId));
    }

    @Transactional(readOnly = true)
    public List<MeasurementDto> findMeasurements(Long routeId) {
        getRoute(routeId);
        return measurementRepository.findByRouteIdOrderByTsAscSeqAsc(routeId).stream()
                .map(MeasurementDto::from)
                .toList();
    }

    @Transactional
    public void deleteMeasurement(Long routeId, Long measurementId) {
        getRoute(routeId);
        if (measurementRepository.deleteFromRoute(routeId, measurementId) == 0) {
            throw new NotFoundException("Measurement " + measurementId + " not found in route " + routeId);
        }
        routeRepository.refreshStatistics(routeId);
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

    private Route getRoute(Long routeId) {
        return routeRepository.findById(routeId)
                .orElseThrow(() -> new NotFoundException("Route not found: " + routeId));
    }
}
