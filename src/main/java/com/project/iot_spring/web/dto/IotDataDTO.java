package com.project.iot_spring.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IotDataDTO {

    private long id;
    private double latitude;
    private double longitude;
    private double usvPerHour;
    private int cpm;
    private LocalDateTime timestamp;

}
