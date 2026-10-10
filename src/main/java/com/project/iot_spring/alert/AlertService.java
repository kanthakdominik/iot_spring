package com.project.iot_spring.alert;

import com.project.iot_spring.common.Db;
import com.project.iot_spring.event.EventService;
import com.project.iot_spring.event.EventTypes;
import com.project.iot_spring.live.LiveEvent;
import com.project.iot_spring.web.NotFoundException;
import com.project.iot_spring.web.PageResponse;
import com.project.iot_spring.web.PageResponse.Paging;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Evaluates alert rules (docs/V2.1-SPEC.md, section 3.4) and manages raised alerts.
 * <ul>
 *   <li>triggers ({@link AlertTrigger}) are published by ingest and evaluated after its transaction commits,
 *       one by one on a dedicated thread - evaluation never blocks or breaks storing samples</li>
 *   <li>at most one alert per (rule, device) within the rule's cooldown, checked against the {@code alerts}
 *       table (so it also holds after a restart)</li>
 *   <li>an alert = row in {@code alerts} + event {@code ALERT} (WARN) + SSE {@code alert} + ntfy push</li>
 * </ul>
 */
@Slf4j
@Service
public class AlertService {

    private static final String SELECT = """
            SELECT a.id, a.rule_id, a.rule_name, a.type, a.device_id, d.name AS device_name, a.route_id, a.ts,
                   a.value, a.threshold, a.latitude, a.longitude, a.message, a.acknowledged_at, a.acknowledged_by
            FROM alerts a LEFT JOIN devices d ON d.id = a.device_id
            """;

    private static final RowMapper<AlertDto> MAPPER = (rs, n) -> new AlertDto(
            rs.getLong("id"),
            Db.longValue(rs, "rule_id"),
            rs.getString("rule_name"),
            rs.getString("type"),
            rs.getString("device_id"),
            rs.getString("device_name"),
            Db.longValue(rs, "route_id"),
            Db.instant(rs, "ts"),
            Db.doubleValue(rs, "value"),
            Db.doubleValue(rs, "threshold"),
            Db.doubleValue(rs, "latitude"),
            Db.doubleValue(rs, "longitude"),
            rs.getString("message"),
            Db.instant(rs, "acknowledged_at"),
            rs.getString("acknowledged_by"));

    private final JdbcClient jdbc;
    private final AlertRuleService ruleService;
    private final EventService eventService;
    private final NtfyNotifier notifier;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("alerts").factory());

    public AlertService(JdbcClient jdbc, AlertRuleService ruleService, EventService eventService,
                        NtfyNotifier notifier, ApplicationEventPublisher publisher,
                        TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.ruleService = ruleService;
        this.eventService = eventService;
        this.notifier = notifier;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
    }

    // ------------------------------------------------------------------ evaluation

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTrigger(AlertTrigger trigger) {
        try {
            executor.execute(() -> {
                try {
                    evaluate(trigger);
                } catch (RuntimeException e) {
                    log.error("Alert evaluation failed for {}", trigger, e);
                }
            });
        } catch (RuntimeException e) {
            log.warn("Alert evaluation rejected: {}", e.toString());
        }
    }

    /** Synchronous evaluation (runs on the alert thread; public for tests). */
    public void evaluate(AlertTrigger trigger) {
        for (AlertRuleDto rule : ruleService.rules()) {
            if (!rule.enabled() || !rule.appliesTo(trigger.deviceId())) {
                continue;
            }
            switch (trigger) {
                case AlertTrigger.DoseRate d when AlertRuleDto.DOSE_RATE_ABOVE.equals(rule.type())
                        && rule.threshold() != null && d.usv() >= rule.threshold() ->
                        raise(rule, d.deviceId(), d.routeId(), d.ts(), d.usv(), d.latitude(), d.longitude(),
                                String.format(Locale.ROOT, "Dose rate %.3f µSv/h reached the threshold %.3f µSv/h (%s)",
                                        d.usv(), rule.threshold(), d.deviceId()));
                case AlertTrigger.Battery b when AlertRuleDto.BATTERY_BELOW.equals(rule.type())
                        && rule.threshold() != null && b.pct() < rule.threshold() ->
                        raise(rule, b.deviceId(), null, b.ts(), (double) b.pct(), null, null,
                                String.format(Locale.ROOT, "Battery %d%% is below %s%% (%s)",
                                        b.pct(), trimNumber(rule.threshold()), b.deviceId()));
                case AlertTrigger.Offline o when AlertRuleDto.DEVICE_OFFLINE.equals(rule.type()) ->
                        raise(rule, o.deviceId(), null, o.ts(), null, null, null,
                                "Device " + o.deviceId() + " went offline");
                default -> {
                    // rule of another type or condition not met
                }
            }
        }
    }

    private void raise(AlertRuleDto rule, String deviceId, Long routeId, Instant ts, Double value,
                       Double latitude, Double longitude, String message) {
        if (inCooldown(rule, deviceId, ts)) {
            return;
        }
        AlertDto alert = transactionTemplate.execute(status -> {
            AlertDto created = jdbc.sql("""
                            WITH ins AS (
                                INSERT INTO alerts (rule_id, rule_name, type, device_id, route_id, ts, value, threshold,
                                                    latitude, longitude, message)
                                VALUES (:ruleId, :ruleName, :type, :deviceId, :routeId, :ts, :value, :threshold,
                                        :lat, :lon, :message)
                                RETURNING *
                            )
                            """ + SELECT.replace("FROM alerts a", "FROM ins a"))
                    .param("ruleId", rule.id())
                    .param("ruleName", rule.name())
                    .param("type", rule.type())
                    .param("deviceId", deviceId)
                    .param("routeId", new SqlParameterValue(Types.BIGINT, routeId))
                    .param("ts", Db.utc(ts))
                    .param("value", new SqlParameterValue(Types.DOUBLE, value))
                    .param("threshold", new SqlParameterValue(Types.DOUBLE, rule.threshold()))
                    .param("lat", new SqlParameterValue(Types.DOUBLE, latitude))
                    .param("lon", new SqlParameterValue(Types.DOUBLE, longitude))
                    .param("message", message.length() <= 300 ? message : message.substring(0, 300))
                    .query(MAPPER)
                    .single();
            eventService.log(deviceId, routeId, EventTypes.ALERT, EventTypes.WARN, message,
                    Map.of("alertId", created.id(), "ruleId", rule.id(), "type", rule.type()));
            publisher.publishEvent(LiveEvent.alert(created));
            return created;
        });
        log.info("Alert raised: {}", message);
        if (alert != null && rule.notifyPush()) {
            notifier.send(rule.name(), message, AlertRuleDto.DOSE_RATE_ABOVE.equals(rule.type()));
        }
    }

    private boolean inCooldown(AlertRuleDto rule, String deviceId, Instant ts) {
        if (rule.cooldownMinutes() == null || rule.cooldownMinutes() <= 0) {
            return false;
        }
        Instant last = jdbc.sql("""
                        SELECT max(ts) AS last FROM alerts WHERE rule_id = :ruleId AND device_id = :deviceId""")
                .param("ruleId", rule.id())
                .param("deviceId", deviceId)
                .query((rs, n) -> Db.instant(rs, "last"))
                .list().stream().filter(Objects::nonNull).findFirst().orElse(null);
        return last != null && ts.isBefore(last.plus(Duration.ofMinutes(rule.cooldownMinutes())));
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** Waits until all queued evaluations have finished (used by tests). */
    public void awaitIdle(Duration timeout) {
        try {
            executor.submit(() -> { }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Alert evaluation did not finish in " + timeout, e);
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ alert list

    @Transactional(readOnly = true)
    public PageResponse<AlertDto> find(boolean openOnly, Paging paging) {
        String where = openOnly ? " WHERE a.acknowledged_at IS NULL" : "";
        long total = jdbc.sql("SELECT count(*) FROM alerts a" + where).query(Long.class).single();
        List<AlertDto> content = jdbc.sql(SELECT + where + " ORDER BY a.ts DESC, a.id DESC LIMIT :limit OFFSET :offset")
                .param("limit", paging.size())
                .param("offset", paging.offset())
                .query(MAPPER)
                .list();
        return PageResponse.of(content, paging, total);
    }

    public long countOpen() {
        return jdbc.sql("SELECT count(*) FROM alerts WHERE acknowledged_at IS NULL").query(Long.class).single();
    }

    @Transactional
    public AlertDto acknowledge(long id, String username) {
        jdbc.sql("""
                        UPDATE alerts SET acknowledged_at = now(), acknowledged_by = :user
                        WHERE id = :id AND acknowledged_at IS NULL""")
                .param("id", id)
                .param("user", username)
                .update();
        return findOne(id).orElseThrow(() -> new NotFoundException("Alert not found: " + id));
    }

    @Transactional
    public int acknowledgeAll(String username) {
        return jdbc.sql("""
                        UPDATE alerts SET acknowledged_at = now(), acknowledged_by = :user
                        WHERE acknowledged_at IS NULL""")
                .param("user", username)
                .update();
    }

    private Optional<AlertDto> findOne(long id) {
        return jdbc.sql(SELECT + " WHERE a.id = :id").param("id", id).query(MAPPER).optional();
    }
}
