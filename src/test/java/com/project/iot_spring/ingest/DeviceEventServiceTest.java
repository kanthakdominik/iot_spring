package com.project.iot_spring.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceEventServiceTest {

    @Test
    void signalQuality99IsUnknown() {
        assertThat(DeviceEventService.normalizeCsq(18)).isEqualTo(18);
        assertThat(DeviceEventService.normalizeCsq(0)).isZero();
        assertThat(DeviceEventService.normalizeCsq(31)).isEqualTo(31);
        assertThat(DeviceEventService.normalizeCsq(99)).isNull();
        assertThat(DeviceEventService.normalizeCsq(-1)).isNull();
        assertThat(DeviceEventService.normalizeCsq(null)).isNull();
    }
}
