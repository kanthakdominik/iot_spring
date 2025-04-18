package com.project.iot_spring.web.service;

import com.project.iot_spring.database.repository.IotDataRepository;
import com.project.iot_spring.database.dao.Route;
import com.project.iot_spring.database.repository.RouteRepository;
import com.project.iot_spring.web.dto.IotDataDTO;
import com.project.iot_spring.web.dto.RouteDTO;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class RouteService {
    private final RouteRepository routeRepository;
    private final IotDataRepository iotDataRepository;

    public List<RouteDTO> getAllRoutes() {
        return routeRepository.findAll().stream()
                .map(route -> new RouteDTO(route.getId(), route.getName()))
                .collect(Collectors.toList());
    }

    public RouteDTO getRoute(Integer routeId) {
        if (routeId == null) {
            throw new IllegalArgumentException("RouteId cannot be null");
        }

        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new EntityNotFoundException("Route not found with id: " + routeId));

        return new RouteDTO(route.getId(), route.getName());
    }

    public List<IotDataDTO> getRouteData(Integer routeId) {
        if (routeId == null) {
            throw new IllegalArgumentException("RouteId cannot be null");
        }

        return iotDataRepository.findByRouteId(routeId).stream()
                .map(data -> new IotDataDTO(
                        data.getId(),
                        data.getLatitude(),
                        data.getLongitude(),
                        data.getUsvPerHour(),
                        data.getCpm(),
                        data.getTimestamp()))
                .collect(Collectors.toList());
    }

    public void updateRouteName(Integer routeId, String newName) {
        if (routeId == null || newName == null || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("RouteId and name cannot be null or empty");
        }
        if (newName.length() > 255 || !newName.matches("^[a-zA-Z0-9\\s-_]+$")) {
            throw new IllegalArgumentException("Invalid route name format");
        }

        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new EntityNotFoundException("Route not found with id: " + routeId));
        route.setName(newName);
        routeRepository.save(route);
    }

    @Transactional
    public void deleteRouteWithIotData(Integer routeId) {
        if (routeId == null) {
            throw new IllegalArgumentException("RouteId cannot be null");
        }

        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new EntityNotFoundException("Route not found with id: " + routeId));
        iotDataRepository.deleteAllByRouteId(routeId);
        routeRepository.delete(route);
    }

    @Transactional
    public void deleteIotData(Integer routeId, Long iotDataId) {
        if (routeId == null || iotDataId == null) {
            throw new IllegalArgumentException("RouteId and IotDataId cannot be null");
        }

        iotDataRepository.deleteById(iotDataId, routeId);
    }
}