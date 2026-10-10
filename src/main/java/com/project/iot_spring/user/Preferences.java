package com.project.iot_spring.user;

import java.util.List;
import java.util.Map;

/**
 * UI preferences stored on the server ({@code users.preferences}, JSONB). Missing or unknown stored values
 * fall back to the defaults (first value of each list).
 */
public record Preferences(String theme, String language, String doseUnit, String mapLayer) {

    static final List<String> THEMES = List.of("system", "light", "dark");
    static final List<String> LANGUAGES = List.of("pl", "en");
    static final List<String> DOSE_UNITS = List.of("uSv", "nSv", "uR");
    static final List<String> MAP_LAYERS = List.of("osm", "satellite", "topo", "dark");

    public static final Preferences DEFAULTS =
            new Preferences(THEMES.getFirst(), LANGUAGES.getFirst(), DOSE_UNITS.getFirst(), MAP_LAYERS.getFirst());

    /** Stored JSON object -> preferences, tolerant (unknown values = defaults). */
    public static Preferences fromStored(Map<String, ?> stored) {
        if (stored == null) {
            return DEFAULTS;
        }
        return new Preferences(
                orDefault(stored.get("theme"), THEMES),
                orDefault(stored.get("language"), LANGUAGES),
                orDefault(stored.get("doseUnit"), DOSE_UNITS),
                orDefault(stored.get("mapLayer"), MAP_LAYERS));
    }

    /** Request body -> preferences: missing fields get defaults, unknown values are rejected (400). */
    public Preferences validated() {
        return new Preferences(
                check("theme", theme, THEMES),
                check("language", language, LANGUAGES),
                check("doseUnit", doseUnit, DOSE_UNITS),
                check("mapLayer", mapLayer, MAP_LAYERS));
    }

    private static String orDefault(Object value, List<String> allowed) {
        return value instanceof String s && allowed.contains(s) ? s : allowed.getFirst();
    }

    private static String check(String field, String value, List<String> allowed) {
        if (value == null) {
            return allowed.getFirst();
        }
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("Invalid " + field + ": " + value + ". Allowed: " + allowed);
        }
        return value;
    }
}
