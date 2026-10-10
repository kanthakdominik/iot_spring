package com.project.iot_spring.event;

import com.project.iot_spring.common.Db;
import com.project.iot_spring.live.LiveEvent;
import com.project.iot_spring.web.PageResponse;
import com.project.iot_spring.web.PageResponse.Paging;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Event log ({@code device_events}). Writes join the caller's transaction; every written event is also sent
 * to the live stream (SSE {@code event}) after commit.
 */
@Service
public class EventService {

    static final int MAX_MESSAGE = 500;

    private static final RowMapper<EventDto> MAPPER = (rs, n) -> new EventDto(
            rs.getLong("id"),
            rs.getString("device_id"),
            rs.getString("device_name"),
            Db.longValue(rs, "route_id"),
            Db.instant(rs, "ts"),
            rs.getString("type"),
            rs.getString("severity"),
            rs.getString("message"));

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher publisher;

    public EventService(JdbcClient jdbc, JsonMapper jsonMapper, ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.publisher = publisher;
    }

    /**
     * @param deviceId must exist in {@code devices} (or be null for a system event)
     * @param data     optional details stored as JSONB
     */
    @Transactional
    public EventDto log(String deviceId, Long routeId, String type, String severity, String message,
                        Map<String, ?> data) {
        String text = message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE);
        EventDto event = jdbc.sql("""
                        WITH ins AS (
                            INSERT INTO device_events (device_id, route_id, type, severity, message, data)
                            VALUES (:deviceId, :routeId, :type, :severity, :message, CAST(:data AS jsonb))
                            RETURNING id, device_id, route_id, ts, type, severity, message
                        )
                        SELECT ins.*, d.name AS device_name
                        FROM ins LEFT JOIN devices d ON d.id = ins.device_id
                        """)
                .param("deviceId", deviceId)
                .param("routeId", routeId)
                .param("type", type)
                .param("severity", severity)
                .param("message", text)
                .param("data", toJson(data))
                .query(MAPPER)
                .single();
        publisher.publishEvent(LiveEvent.event(event));
        return event;
    }

    @Transactional(readOnly = true)
    public PageResponse<EventDto> find(String deviceId, String type, Long routeId, Paging paging) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (deviceId != null && !deviceId.isBlank()) {
            where.add("e.device_id = :deviceId");
            params.put("deviceId", deviceId);
        }
        if (type != null && !type.isBlank()) {
            if (!EventTypes.ALL.contains(type)) {
                throw new IllegalArgumentException("Unknown event type: " + type);
            }
            where.add("e.type = :type");
            params.put("type", type);
        }
        if (routeId != null) {
            where.add("e.route_id = :routeId");
            params.put("routeId", routeId);
        }
        String whereSql = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);

        long total = jdbc.sql("SELECT count(*) FROM device_events e" + whereSql)
                .params(params)
                .query(Long.class)
                .single();
        List<EventDto> content = jdbc.sql("""
                        SELECT e.id, e.device_id, d.name AS device_name, e.route_id, e.ts, e.type, e.severity, e.message
                        FROM device_events e LEFT JOIN devices d ON d.id = e.device_id
                        """ + whereSql + " ORDER BY e.ts DESC, e.id DESC LIMIT :limit OFFSET :offset")
                .params(params)
                .param("limit", paging.size())
                .param("offset", paging.offset())
                .query(MAPPER)
                .list();
        return PageResponse.of(content, paging, total);
    }

    private String toJson(Map<String, ?> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(data);
        } catch (JacksonException e) {
            return null;
        }
    }
}
