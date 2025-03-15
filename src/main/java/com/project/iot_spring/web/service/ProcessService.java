package com.project.iot_spring.web.service;

import com.project.iot_spring.database.repository.IotDataRepository;
import com.project.iot_spring.database.repository.RouteRepository;
import com.project.iot_spring.database.dao.IotData;
import com.project.iot_spring.database.dao.Route;
import com.project.iot_spring.validator.SensorDataValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.logging.Logger;

@Service
public class ProcessService {

    private static final Logger LOGGER = Logger.getLogger(ProcessService.class.getName());

    private final IotDataRepository iotDataRepository;
    private final RouteRepository routeRepository;

    private Integer currentRouteId = null;

    public ProcessService(IotDataRepository iotDataRepository, RouteRepository routeRepository) {
        this.iotDataRepository = iotDataRepository;
        this.routeRepository = routeRepository;
    }

    @Transactional
    public void saveToDatabase(String payload) {
        IotData data = SensorDataValidator.validateAndParse(payload);

        if (data != null) {
            LOGGER.info("Saving to database: " + data);

            if (currentRouteId == null || !currentRouteId.equals(data.getRouteId())) {
                try {
                    int newId = data.getRouteId();
                    String newName = "Route-" + newId;
                    Route newRoute = new Route(newId, newName);

                    routeRepository.save(newRoute);
                    currentRouteId = newId;

                    LOGGER.info("Created new route: " + newName);
                } catch (Exception e) {
                    LOGGER.severe("Failed to create new route: " + e.getMessage());
                    return;
                }
            }

            iotDataRepository.save(data);
        } else {
            LOGGER.warning("Invalid data received, skipping save.");
        }
    }
}
