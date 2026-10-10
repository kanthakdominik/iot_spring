package com.project.iot_spring.user;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreferencesTest {

    @Test
    void missingOrUnknownStoredValuesGetDefaults() {
        assertThat(Preferences.fromStored(Map.of())).isEqualTo(Preferences.DEFAULTS);
        assertThat(Preferences.fromStored(null)).isEqualTo(Preferences.DEFAULTS);
        assertThat(Preferences.fromStored(Map.of("theme", "dark", "language", "xx", "mapLayer", 5)))
                .isEqualTo(new Preferences("dark", "pl", "uSv", "osm"));
    }

    @Test
    void defaultsAreTheFirstAllowedValues() {
        assertThat(Preferences.DEFAULTS).isEqualTo(new Preferences("system", "pl", "uSv", "osm"));
    }

    @Test
    void requestIsValidated() {
        assertThat(new Preferences("light", null, "uR", null).validated())
                .isEqualTo(new Preferences("light", "pl", "uR", "osm"));
        assertThatThrownBy(() -> new Preferences("light", "de", null, null).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language");
        assertThatThrownBy(() -> new Preferences(null, null, "Sv", null).validated())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
