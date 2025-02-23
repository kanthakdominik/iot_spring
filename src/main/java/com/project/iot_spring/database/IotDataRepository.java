package com.project.iot_spring.database;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IotDataRepository extends JpaRepository<IotData, Long>, IotDataRepositoryCustom {
}
