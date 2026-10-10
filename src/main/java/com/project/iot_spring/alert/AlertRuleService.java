package com.project.iot_spring.alert;

import com.project.iot_spring.common.Db;
import com.project.iot_spring.web.NotFoundException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.project.iot_spring.alert.AlertRuleDto.BATTERY_BELOW;
import static com.project.iot_spring.alert.AlertRuleDto.DEVICE_OFFLINE;
import static com.project.iot_spring.alert.AlertRuleDto.DOSE_RATE_ABOVE;

/**
 * CRUD of alert rules. The rules are kept in memory (they are checked for every sample) and reloaded after
 * every change.
 */
@Service
public class AlertRuleService {

    static final Set<String> TYPES = Set.of(DOSE_RATE_ABOVE, BATTERY_BELOW, DEVICE_OFFLINE);
    static final int DEFAULT_COOLDOWN = 15;

    private static final RowMapper<AlertRuleDto> MAPPER = (rs, n) -> new AlertRuleDto(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("type"),
            Db.doubleValue(rs, "threshold"),
            rs.getString("device_id"),
            rs.getBoolean("enabled"),
            rs.getInt("cooldown_minutes"),
            rs.getBoolean("notify_push"));

    private final JdbcClient jdbc;
    private volatile List<AlertRuleDto> cache;

    public AlertRuleService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Cached rules (loaded on first use). */
    public List<AlertRuleDto> rules() {
        List<AlertRuleDto> rules = cache;
        if (rules == null) {
            rules = reload();
        }
        return rules;
    }

    public synchronized List<AlertRuleDto> reload() {
        List<AlertRuleDto> rules = List.copyOf(jdbc.sql("""
                        SELECT id, name, type, threshold, device_id, enabled, cooldown_minutes, notify_push
                        FROM alert_rules ORDER BY id""")
                .query(MAPPER)
                .list());
        cache = rules;
        return rules;
    }

    public List<AlertRuleDto> findAll() {
        return reload();
    }

    public AlertRuleDto create(AlertRuleDto request) {
        AlertRuleDto rule = validate(request);
        long id = jdbc.sql("""
                        INSERT INTO alert_rules (name, type, threshold, device_id, enabled, cooldown_minutes, notify_push)
                        VALUES (:name, :type, :threshold, :deviceId, :enabled, :cooldown, :push)
                        RETURNING id""")
                .params(params(rule))
                .query(Long.class)
                .single();
        reload();
        return find(id);
    }

    public AlertRuleDto update(long id, AlertRuleDto request) {
        AlertRuleDto rule = validate(request);
        int updated = jdbc.sql("""
                        UPDATE alert_rules SET name = :name, type = :type, threshold = :threshold,
                            device_id = :deviceId, enabled = :enabled, cooldown_minutes = :cooldown,
                            notify_push = :push
                        WHERE id = :id""")
                .params(params(rule))
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new NotFoundException("Alert rule not found: " + id);
        }
        reload();
        return find(id);
    }

    public void delete(long id) {
        int deleted = jdbc.sql("DELETE FROM alert_rules WHERE id = :id").param("id", id).update();
        if (deleted == 0) {
            throw new NotFoundException("Alert rule not found: " + id);
        }
        reload();
    }

    private AlertRuleDto find(long id) {
        return rules().stream().filter(r -> r.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Alert rule not found: " + id));
    }

    /** Checks the request and fills in defaults. */
    AlertRuleDto validate(AlertRuleDto r) {
        if (r.type() == null || !TYPES.contains(r.type())) {
            throw new IllegalArgumentException("Unknown rule type: " + r.type() + ". Allowed: " + TYPES);
        }
        Double threshold = r.threshold();
        switch (r.type()) {
            case DOSE_RATE_ABOVE -> {
                if (threshold == null || threshold.isNaN() || threshold <= 0 || threshold > 1000) {
                    throw new IllegalArgumentException("DOSE_RATE_ABOVE needs a threshold in uSv/h (0 < x <= 1000)");
                }
            }
            case BATTERY_BELOW -> {
                if (threshold == null || threshold.isNaN() || threshold <= 0 || threshold > 100) {
                    throw new IllegalArgumentException("BATTERY_BELOW needs a threshold in % (0 < x <= 100)");
                }
            }
            default -> threshold = null;
        }
        String deviceId = r.deviceId() == null || r.deviceId().isBlank() ? null : r.deviceId().trim();
        if (deviceId != null) {
            boolean exists = jdbc.sql("SELECT count(*) FROM devices WHERE id = :id").param("id", deviceId)
                    .query(Long.class).single() > 0;
            if (!exists) {
                throw new IllegalArgumentException("Unknown device: " + deviceId);
            }
        }
        String name = r.name() == null ? "" : r.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Rule name cannot be empty");
        }
        return new AlertRuleDto(null, name, r.type(), threshold, deviceId,
                r.enabled() == null || r.enabled(),
                r.cooldownMinutes() == null ? DEFAULT_COOLDOWN : r.cooldownMinutes(),
                r.notifyPush() == null || r.notifyPush());
    }

    private static Map<String, Object> params(AlertRuleDto r) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", r.name());
        params.put("type", r.type());
        params.put("threshold", new SqlParameterValue(Types.DOUBLE, r.threshold()));
        params.put("deviceId", new SqlParameterValue(Types.VARCHAR, r.deviceId()));
        params.put("enabled", r.enabled());
        params.put("cooldown", r.cooldownMinutes());
        params.put("push", r.notifyPush());
        return params;
    }
}
