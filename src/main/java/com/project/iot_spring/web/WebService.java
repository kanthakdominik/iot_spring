package com.project.iot_spring.web;

import com.project.iot_spring.database.IotDataRepository;
import com.project.iot_spring.database.RouteRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class WebService {
    private final RouteRepository routeRepository;
    private final IotDataRepository iotDataRepository;

    public List<RouteDTO> getAllRoutes() {
        return routeRepository.findAll().stream()
                .map(route -> new RouteDTO(route.getId(), route.getName()))
                .collect(Collectors.toList());
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
                        data.getUsvPerHour()))
                .collect(Collectors.toList());
    }
}