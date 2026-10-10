package com.project.iot_spring.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Every 30 s recomputes the statistics of routes marked {@code stats_dirty} by ingest (max 50 per run),
 * each route in its own transaction. Can be switched off with {@code app.stats.job.enabled=false} (tests).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.stats.job.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RouteStatsJob {

    static final int BATCH = 50;

    private final RouteStatsService statsService;

    @Scheduled(fixedDelay = 30, initialDelay = 15, timeUnit = TimeUnit.SECONDS)
    public void run() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Route statistics job failed", e);
        }
    }

    /** @return number of recomputed routes */
    public int runOnce() {
        List<Long> ids = statsService.findDirty(BATCH);
        int done = 0;
        for (Long id : ids) {
            try {
                if (statsService.recompute(id)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.warn("Cannot recompute statistics of route {}: {}", id, e.getMessage());
            }
        }
        if (done > 0) {
            log.debug("Recomputed statistics of {} route(s)", done);
        }
        return done;
    }
}
