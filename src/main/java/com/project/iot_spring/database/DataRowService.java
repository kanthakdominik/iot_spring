package com.project.iot_spring.database;

import com.project.iot_spring.validator.SensorDataValidator;
import org.springframework.stereotype.Service;

import java.util.logging.Logger;

@Service
public class DataRowService {

    private static final Logger LOGGER = Logger.getLogger(DataRowService.class.getName());

    private final DataRowRepository repository;

    public DataRowService(DataRowRepository repository) {
        this.repository = repository;
    }

    public void saveToDatabase(String payload) {
        DataRow data = SensorDataValidator.validateAndParse(payload);

        if (data != null) {
            LOGGER.info("Saving to database: " + data);
            System.out.println("Valid data: " + data);

            repository.save(data);
        } else {
            LOGGER.warning("Invalid data received, skipping save.");
        }
    }
}
