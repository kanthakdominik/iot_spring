package com.project.iot_spring.web;

import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/routes")
@CrossOrigin(origins = "http://localhost:4200")
@AllArgsConstructor
public class WebController {

    private final WebService webService;

    @GetMapping
    public ResponseEntity<List<RouteDTO>> getAllRoutes() {
        return ResponseEntity.ok(webService.getAllRoutes());
    }

    @GetMapping("/{routeId}/data")
    public ResponseEntity<List<IotDataDTO>> getRouteData(@PathVariable Integer routeId) {
        return ResponseEntity.ok(webService.getRouteData(routeId));
    }
}
