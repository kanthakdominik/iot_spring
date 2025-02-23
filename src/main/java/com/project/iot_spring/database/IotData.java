package com.project.iot_spring.database;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "iot_data")
public class IotData {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "iot_data_id_seq")
    @SequenceGenerator(name = "iot_data_id_seq", sequenceName = "iot_data_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "route_id", nullable = false)
    private int routeId;

    private double latitude;
    private double longitude;

    private LocalDateTime timestamp;

    private int cps;
    private int cpm;

    @Column(name = "usv_per_hr")
    private double usvPerHour;

    private String mode;

    public IotData() {
    }

    public IotData(int routeId, double latitude, double longitude, LocalDateTime timestamp, int cps, int cpm, double usvPerHour, String mode) {
        this.routeId = routeId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = timestamp;
        this.cps = cps;
        this.cpm = cpm;
        this.usvPerHour = usvPerHour;
        this.mode = mode;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getRouteId() {
        return routeId;
    }

    public void setRouteId(int routeId) {
        this.routeId = routeId;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public int getCps() {
        return cps;
    }

    public void setCps(int cps) {
        this.cps = cps;
    }

    public int getCpm() {
        return cpm;
    }

    public void setCpm(int cpm) {
        this.cpm = cpm;
    }

    public double getUsvPerHour() {
        return usvPerHour;
    }

    public void setUsvPerHour(double usvPerHour) {
        this.usvPerHour = usvPerHour;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}