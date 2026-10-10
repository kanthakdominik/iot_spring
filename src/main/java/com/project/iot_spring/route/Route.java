package com.project.iot_spring.route;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A measurement route. Identified in the database by {@link #id}; on the device side by the pair
 * (deviceId, deviceRouteNo). Created by the ingest service on the first sample of a route.
 * Statistics, preview and share token are read with plain SQL ({@link RouteQueries}).
 */
@Entity
@Table(name = "routes")
@Getter
@Setter
@NoArgsConstructor
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, length = 32, updatable = false)
    private String deviceId;

    @Column(name = "device_route_no", nullable = false, updatable = false)
    private Integer deviceRouteNo;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 2000)
    private String description;

    // maintained by ingest and RouteStatsService (plain SQL) - never written by JPA,
    // so renaming a route cannot overwrite values updated concurrently by ingest
    @Column(name = "started_at", insertable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at", insertable = false, updatable = false)
    private Instant endedAt;

    @Column(name = "sample_count", nullable = false, insertable = false, updatable = false)
    private int sampleCount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
