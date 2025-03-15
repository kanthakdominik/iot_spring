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


-- PROVIDE REQUIRED INFORMATION
INSERT INTO users (username, password, role)
SELECT 'admin', 'your-bcrypt-hash-here', 'ADMIN'
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE username = 'admin'
);