package com.project.iot_spring.validator;

import com.project.iot_spring.database.dao.IotData;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.logging.Logger;

public class SensorDataValidator {
    private static final Logger LOGGER = Logger.getLogger(SensorDataValidator.class.getName());

    public static IotData validateAndParse(String payload) {
        try {
            String[] parts = payload.split(",\\s*");

            if (parts.length != 11) {
                LOGGER.warning("Invalid data format: " + payload);
                return null;
            }

            Integer routeId = parseRouteId(parts[0], payload);
            double latitude = parseLatitude(parts[1], payload);
            double longitude = parseLongitude(parts[2], payload);
            LocalDateTime timestamp = parseTimestamp(parts[3], payload);
            int cps = parseInt(parts[5], "CPS", 0, 65535, payload);
            int cpm = parseInt(parts[7], "CPM", 0, 99999, payload);
            double usvPerHour = parseDouble(parts[9], "uSv/hr", 0.0, 100.0, payload);
            String mode = checkMode(parts[10], payload);

            if (routeId == null || latitude == -1 || longitude == -1 || timestamp == null || cps == -1 || cpm == -1 || usvPerHour == -1 || mode == null) {
                LOGGER.warning("Invalid data, skipping insert: " + payload);
                return null;
            }

            return new IotData(null, routeId, latitude, longitude, timestamp, cps, cpm, usvPerHour, mode);

        } catch (Exception e) {
            LOGGER.severe("Error processing MQTT message: " + payload + " -> " + e.getMessage());
            return null;
        }
    }

    private static Integer parseRouteId(String value, String fullPayload) {
        try {
            int routeId = Integer.parseInt(value.trim());
            if (routeId <= 0) {
                LOGGER.warning("Invalid route ID (must be positive): " + value + " in payload: " + fullPayload);
                return null;
            }
            return routeId;
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid route ID format: " + value + " in payload: " + fullPayload);
            return null;
        }
    }

    private static double parseLatitude(String value, String fullPayload) {
        double latitude =  parseDouble(value, "Latitude", -90.0, 90.0, fullPayload);
        return latitude == 0.0 ? -1 : latitude;
    }

    private static double parseLongitude(String value, String fullPayload) {
        double longitude =  parseDouble(value, "Longitude", -180.0, 180.0, fullPayload);
        return longitude == 0.0 ? -1 : longitude;
    }

    private static double parseDouble(String value, String fieldName, double min, double max, String fullPayload) {
        try {
            double result = Double.parseDouble(value);
            if (result < min || result > max) {
                LOGGER.warning("Out of range " + fieldName + ": " + value + " in payload: " + fullPayload);
                return -1;
            }
            return result;
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid " + fieldName + ": " + value + " in payload: " + fullPayload);
            return -1;
        }
    }

    private static int parseInt(String value, String fieldName, int min, int max, String fullPayload) {
        try {
            int result = Integer.parseInt(value);
            if (result < min || result > max) {
                LOGGER.warning("Out of range " + fieldName + ": " + value + " in payload: " + fullPayload);
                return -1;
            }
            return result;
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid " + fieldName + ": " + value + " in payload: " + fullPayload);
            return -1;
        }
    }

    private static LocalDateTime parseTimestamp(String value, String fullPayload) {
        try {
            return LocalDateTime.parse(value.replace(" ", "T"), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            LOGGER.warning("Invalid Timestamp: " + value + " in payload: " + fullPayload);
            return null;
        }
    }

    private static String checkMode(String mode, String fullPayload) {
        List<String> validModes = List.of("SLOW", "FAST", "INST");
        mode = mode.trim();

        if (!validModes.contains(mode)) {
            LOGGER.warning("Invalid mode: " + mode + " in payload: " + fullPayload);
            return null;
        }
        return mode;
    }
}
