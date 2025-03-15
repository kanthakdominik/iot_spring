package com.project.iot_spring.database.repository;

import com.project.iot_spring.database.dao.IotData;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class IotDataRepositoryCustomImpl implements IotDataRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<IotData> findByRouteId(int routeId) {
        String tableName = "iot_data_route_" + routeId;
        String sql = "SELECT * FROM " + tableName;

        Query query = entityManager.createNativeQuery(sql);

        List<Object[]> results = query.getResultList();
        return results.stream().map(this::mapToIotData).toList();
    }


    @Transactional
    @Override
    public void deleteAllByRouteId(int routeId) {
        String tableName = "iot_data_route_" + routeId;
        String sql = "TRUNCATE TABLE " + tableName;

        Query query = entityManager.createNativeQuery(sql);
        query.executeUpdate();
    }

    @Transactional
    @Override
    public void deleteById(long iotDataId, int routeId) {
        String tableName = "iot_data_route_" + routeId;
        String sql = "DELETE FROM " + tableName + " WHERE id = :iotDataId";

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("iotDataId", iotDataId);
        query.executeUpdate();
    }


    private IotData mapToIotData(Object[] row) {
        return IotData.builder()
                .id(((Integer) row[0]).longValue())
                .routeId((Integer) row[1])
                .latitude((Double) row[2])
                .longitude((Double) row[3])
                .timestamp(((java.sql.Timestamp) row[4]).toLocalDateTime())
                .cps((Integer) row[5])
                .cpm((Integer) row[6])
                .usvPerHour((Double) row[7])
                .mode((String) row[8])
                .build();
    }
}
