-- 1. Tworzymy nową trasę o ID 888
INSERT INTO routes (id, name)
VALUES (999, 'Combined Route (242 + 243)')
ON CONFLICT (id) DO NOTHING;

-- 2. Kopiujemy punkty pomiarowe z tras 242 i 243 i przypisujemy je do nowej trasy 999
INSERT INTO iot_data (route_id, latitude, longitude, timestamp, cps, cpm, usv_per_hr, mode)
SELECT
    999 AS route_id,
    latitude,
    longitude,
    timestamp,
    cps,
    cpm,
    usv_per_hr,
    mode
FROM iot_data
WHERE route_id IN (242, 243)
ORDER BY timestamp ASC;  -- zachowujemy chronologiczną kolejność punktów

