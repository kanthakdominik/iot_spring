package com.project.iot_spring.stats;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatsServiceTest {

    @Test
    void cellSizeFollowsZoom() {
        assertThat(StatsService.cellDeg(3)).isEqualTo(360.0 / 8 / 16);
        assertThat(StatsService.cellDeg(12)).isEqualTo(360.0 / 4096 / 16);
        assertThat(StatsService.cellDeg(18)).isEqualTo(360.0 / 262_144 / 16);
        // clamped to 3..18
        assertThat(StatsService.cellDeg(0)).isEqualTo(StatsService.cellDeg(3));
        assertThat(StatsService.cellDeg(25)).isEqualTo(StatsService.cellDeg(18));
    }
}
