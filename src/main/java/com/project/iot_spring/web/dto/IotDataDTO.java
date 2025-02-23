package com.project.iot_spring.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IotDataDTO {

    private double latitude;
    private double longitude;
    private double usvPerHour;

}
