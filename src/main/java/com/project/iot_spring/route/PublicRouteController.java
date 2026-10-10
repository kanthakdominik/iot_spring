package com.project.iot_spring.route;

import com.project.iot_spring.route.RouteDtos.MarkerDto;
import com.project.iot_spring.route.RouteDtos.MeasurementDto;
import com.project.iot_spring.route.RouteDtos.RouteDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Routes shared with a link ({@code /share/<token>} in the frontend) - no login required, read only. */
@RestController
@RequestMapping("/api/public/routes/{token}")
@RequiredArgsConstructor
public class PublicRouteController {

    private final RouteService routeService;

    @GetMapping
    public RouteDto route(@PathVariable String token) {
        return routeService.findShared(token);
    }

    @GetMapping("/measurements")
    public List<MeasurementDto> measurements(@PathVariable String token) {
        return routeService.findSharedMeasurements(token);
    }

    @GetMapping("/markers")
    public List<MarkerDto> markers(@PathVariable String token) {
        return routeService.findSharedMarkers(token);
    }
}
