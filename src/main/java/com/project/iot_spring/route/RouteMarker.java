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

/** A labelled point of interest inside a route (e.g. "Am-241 source here"). */
@Entity
@Table(name = "route_markers")
@Getter
@Setter
@NoArgsConstructor
public class RouteMarker {

    public static final String SOURCE_USER = "user";
    public static final String SOURCE_DEVICE = "device";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Column(nullable = false)
    private Instant ts;

    private Double latitude;

    private Double longitude;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(nullable = false, length = 10)
    private String source;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
