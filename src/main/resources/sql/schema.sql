CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL
);

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