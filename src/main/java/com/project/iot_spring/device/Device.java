package com.project.iot_spring.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A measuring device. The id is the MQTT client id / certificate CN (e.g. "esp32-1").
 * Rows are created automatically when a device publishes for the first time.
 */
@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
public class Device {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(nullable = false)
    private boolean online;

    @Column(name = "fw_version", length = 32)
    private String fwVersion;

    @Column(name = "current_route_no")
    private Integer currentRouteNo;

    @Column(name = "last_reset", length = 32)
    private String lastReset;

    @Column(name = "battery_mv")
    private Integer batteryMv;

    @Column(name = "battery_pct")
    private Integer batteryPct;

    @Column(name = "battery_at")
    private Instant batteryAt;

    /** GSM signal quality 0..31 (AT+CSQ), null = unknown. */
    @Column(name = "signal_csq")
    private Short signalCsq;

    @Column(name = "signal_at")
    private Instant signalAt;
}
