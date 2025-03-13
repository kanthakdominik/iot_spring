package com.project.iot_spring.database;

import java.util.List;

public interface IotDataRepositoryCustom {
    List<IotData> findByRouteId(int routeId);
    void deleteAllByRouteId(int routeId);
    void deleteById(long iotDataId, int routeId);
}
