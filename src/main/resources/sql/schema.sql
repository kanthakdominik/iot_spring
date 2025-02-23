CREATE TABLE routes (
    id INT PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE iot_data (
    id SERIAL,
    route_id INT NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    cps INT NOT NULL,
    cpm INT NOT NULL,
    usv_per_hr DOUBLE PRECISION NOT NULL,
    mode VARCHAR(20) NOT NULL,
    PRIMARY KEY (id, route_id),
    FOREIGN KEY (route_id) REFERENCES routes(id)
) PARTITION BY LIST (route_id);

DO $$
DECLARE
    route INT;
    partition_name TEXT;
    sql TEXT;
BEGIN
    FOR route IN 1..1000 LOOP
        partition_name := 'iot_data_route_' || route;
        sql := 'CREATE TABLE IF NOT EXISTS ' || partition_name || ' PARTITION OF iot_data FOR VALUES IN (' || route || ')';
        EXECUTE sql;
    END LOOP;
END $$;