package com.project.iot_spring.database;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "iot_data")
@Data
@NoArgsConstructor
@AllArgsConstructor
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

}