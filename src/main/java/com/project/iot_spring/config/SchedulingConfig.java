package com.project.iot_spring.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled jobs: RouteStatsJob (statistics) and the SSE ping. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
