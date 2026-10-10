package com.project.iot_spring.route;

import org.springframework.data.jpa.repository.JpaRepository;

/** JPA access to routes (rename / delete). Lists and statistics use {@link RouteQueries} (plain SQL). */
public interface RouteRepository extends JpaRepository<Route, Long> {
}
