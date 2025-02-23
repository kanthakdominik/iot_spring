package com.project.iot_spring.web;

import com.project.iot_spring.web.dto.IotDataDTO;
import com.project.iot_spring.web.dto.RouteDTO;
import com.project.iot_spring.web.dto.RouteNameUpdateDTO;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
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

    @PutMapping("/{routeId}/name")
    public ResponseEntity<String> updateRouteName(@PathVariable Integer routeId,
                                                @RequestBody RouteNameUpdateDTO request) {
        try {
            webService.updateRouteName(routeId, request.getNewName());
            return ResponseEntity.ok("Route name updated successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }
}
