package com.project.iot_spring.route;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * One sample of a route (TimescaleDB hypertable "measurements").
 * Read-only for JPA: rows are inserted by {@code TelemetryIngestService} with plain SQL
 * (INSERT ... ON CONFLICT DO NOTHING for de-duplication).
 */
@Entity
@Immutable
@Table(name = "measurements")
@Getter
@NoArgsConstructor
public class Measurement {

    @Id
    private Long id;

    @Column(name = "route_id")
    private Long routeId;

    private Instant ts;

    private int seq;

    @Column(name = "time_source")
    private String timeSource;

    @Column(name = "gps_fix")
    private boolean gpsFix;

    private Double latitude;

    private Double longitude;

    private Short satellites;

    private Float hdop;

    private Float speed;

    private int cps;

    private int cpm;

    @Column(name = "usv_per_hr")
    private double usvPerHour;

    private String mode;
}
