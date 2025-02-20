package com.project.iot_spring.database;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "iot_data")
public class DataRow {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "iot_data_id_seq")
    @SequenceGenerator(name = "iot_data_id_seq", sequenceName = "iot_data_id_seq", allocationSize = 1)
    Long id;

    double latitude;
    double longitude;

    LocalDateTime timestamp;

    int cps;
    int cpm;

    @Column(name = "usv_per_hr")
    double usvPerHour;

    String mode;

    public DataRow(double latitude, double longitude, LocalDateTime timestamp, int cps, int cpm, double usvPerHour, String mode) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = timestamp;
        this.cps = cps;
        this.cpm = cpm;
        this.usvPerHour = usvPerHour;
        this.mode = mode;
    }
}