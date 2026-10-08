package com.project.iot_spring.route;

import com.project.iot_spring.route.RouteDtos.MarkerCreateRequest;
import com.project.iot_spring.route.RouteDtos.MarkerDto;
import com.project.iot_spring.route.RouteDtos.MeasurementDto;
import com.project.iot_spring.route.RouteDtos.RouteDto;
import com.project.iot_spring.route.RouteDtos.RouteUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;

    @GetMapping
    public List<RouteDto> findAll(@RequestParam(required = false) String deviceId) {
        return routeService.findAll(deviceId);
    }

    @GetMapping("/{routeId}")
    public RouteDto find(@PathVariable Long routeId) {
        return routeService.find(routeId);
    }

    @PatchMapping("/{routeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public RouteDto update(@PathVariable Long routeId, @Valid @RequestBody RouteUpdateRequest request) {
        return routeService.update(routeId, request);
    }

    @DeleteMapping("/{routeId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long routeId) {
        routeService.delete(routeId);
    }

    @GetMapping("/{routeId}/measurements")
    public List<MeasurementDto> measurements(@PathVariable Long routeId) {
        return routeService.findMeasurements(routeId);
    }

    @DeleteMapping("/{routeId}/measurements/{measurementId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMeasurement(@PathVariable Long routeId, @PathVariable Long measurementId) {
        routeService.deleteMeasurement(routeId, measurementId);
    }

    @GetMapping("/{routeId}/markers")
    public List<MarkerDto> markers(@PathVariable Long routeId) {
        return routeService.findMarkers(routeId);
    }

    @PostMapping("/{routeId}/markers")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public MarkerDto addMarker(@PathVariable Long routeId, @Valid @RequestBody MarkerCreateRequest request) {
        return routeService.addMarker(routeId, request);
    }

    @DeleteMapping("/{routeId}/markers/{markerId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMarker(@PathVariable Long routeId, @PathVariable Long markerId) {
        routeService.deleteMarker(routeId, markerId);
    }
}
