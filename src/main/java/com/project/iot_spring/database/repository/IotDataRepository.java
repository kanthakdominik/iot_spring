package com.project.iot_spring.database.repository;

import com.project.iot_spring.database.dao.IotData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IotDataRepository extends JpaRepository<IotData, Long>, IotDataRepositoryCustom {
}
