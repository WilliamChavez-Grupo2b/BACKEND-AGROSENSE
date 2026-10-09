-- AgroSense demo data for PostgreSQL. Optional: run it after schema.sql.
-- Safe to run more than once: every statement skips rows that already exist.
--
-- The demo user is created with a password hash that matches no password, so nobody can sign in with it
-- until an application sets one: the backend "seed" profile (SEED_USER_PASSWORD) or the frontend "demo"
-- profile (DEMO_PASSWORD). No password or usable hash is stored in this file.

INSERT INTO users (name, last_name, email, password_hash, role)
SELECT 'Usuario', 'Demo', 'demo@agrosense.co', 'DISABLED', 'farmer'
FROM (VALUES (1)) AS one(x)
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'demo@agrosense.co');

INSERT INTO estates (id_user, name, location, latitude, longitude, area_ha)
SELECT u.id_user, v.name, v.location, v.latitude, v.longitude, v.area_ha
FROM users u
CROSS JOIN (VALUES
        ('Finca La Esperanza', 'Pasto, Nariño', 1.2136000, -77.2811000, 12.50),
        ('Finca El Mirador', 'La Unión, Nariño', 1.6044000, -77.1317000, 6.00)
    ) AS v(name, location, latitude, longitude, area_ha)
WHERE u.email = 'demo@agrosense.co'
  AND NOT EXISTS (SELECT 1 FROM estates e WHERE e.id_user = u.id_user AND e.name = v.name);

-- Humidity, temperature and pH ranges take the column defaults from schema.sql.
INSERT INTO crops (id_estate, name, variety, sowing_date, stage)
SELECT e.id_estate, v.name, v.variety, v.sowing_date, v.stage
FROM estates e
JOIN users u ON u.id_user = e.id_user AND u.email = 'demo@agrosense.co'
JOIN (VALUES
        ('Finca La Esperanza', 'Café', 'Castillo', DATE '2025-04-08', 'FLOWERING'),
        ('Finca La Esperanza', 'Plátano', 'Hartón', DATE '2025-12-08', 'GROWTH'),
        ('Finca El Mirador', 'Papa', 'Pastusa', DATE '2026-08-08', 'GERMINATION')
    ) AS v(estate_name, name, variety, sowing_date, stage) ON v.estate_name = e.name
WHERE NOT EXISTS (SELECT 1 FROM crops c WHERE c.id_estate = e.id_estate AND c.name = v.name);

INSERT INTO sensors (id_crop, sensor_code, sensor_type, location, active, last_reading_at)
SELECT c.id_crop, v.sensor_code, v.sensor_type, v.location, v.active,
       CASE WHEN v.active THEN CURRENT_TIMESTAMP ELSE CURRENT_TIMESTAMP - INTERVAL '3' DAY END
FROM crops c
JOIN estates e ON e.id_estate = c.id_estate
JOIN users u ON u.id_user = e.id_user AND u.email = 'demo@agrosense.co'
JOIN (VALUES
        ('Café', 'AS-001', 'SOIL_MOISTURE', 'Lote norte', TRUE),
        ('Café', 'AS-002', 'AIR_TEMPERATURE', 'Lote norte', TRUE),
        ('Café', 'AS-003', 'PH', 'Lote sur', TRUE),
        ('Plátano', 'AS-004', 'SOIL_MOISTURE', 'Platanera', TRUE),
        ('Plátano', 'AS-005', 'CONDUCTIVITY', 'Platanera', TRUE),
        ('Papa', 'AS-006', 'LIGHT', 'Parcela 1', FALSE)
    ) AS v(crop_name, sensor_code, sensor_type, location, active) ON v.crop_name = c.name
WHERE NOT EXISTS (SELECT 1 FROM sensors s WHERE s.sensor_code = v.sensor_code);

-- One reading per hour for the last 24 hours of every active sensor.

INSERT INTO sensor_readings (id_sensor, reading_value, unit, recorded_at)
SELECT s.id_sensor, v.reading_value, '%', CURRENT_TIMESTAMP - v.hours_ago * INTERVAL '1' HOUR
FROM sensors s
CROSS JOIN (VALUES
        (23, 53.37), (22, 57.27), (21, 60.64), (20, 63.24), (19, 64.92), (18, 65.65),
        (17, 65.42), (16, 64.35), (15, 62.56), (14, 60.22), (13, 57.50), (12, 54.57),
        (11, 51.58), (10, 48.67), (9, 45.94), (8, 43.53), (7, 41.54), (6, 40.08),
        (5, 39.26), (4, 39.17), (3, 39.88), (2, 41.42), (1, 43.75), (0, 46.77)
    ) AS v(hours_ago, reading_value)
WHERE s.sensor_code = 'AS-001'
  AND NOT EXISTS (SELECT 1 FROM sensor_readings r WHERE r.id_sensor = s.id_sensor);

INSERT INTO sensor_readings (id_sensor, reading_value, unit, recorded_at)
SELECT s.id_sensor, v.reading_value, '°C', CURRENT_TIMESTAMP - v.hours_ago * INTERVAL '1' HOUR
FROM sensors s
CROSS JOIN (VALUES
        (23, 20.97), (22, 22.82), (21, 24.52), (20, 25.92), (19, 26.91), (18, 27.43),
        (17, 27.44), (16, 26.96), (15, 26.06), (14, 24.82), (13, 23.38), (12, 21.84),
        (11, 20.32), (10, 18.92), (9, 17.72), (8, 16.77), (7, 16.11), (6, 15.75),
        (5, 15.69), (4, 15.92), (3, 16.41), (2, 17.15), (1, 18.11), (0, 19.26)
    ) AS v(hours_ago, reading_value)
WHERE s.sensor_code = 'AS-002'
  AND NOT EXISTS (SELECT 1 FROM sensor_readings r WHERE r.id_sensor = s.id_sensor);

INSERT INTO sensor_readings (id_sensor, reading_value, unit, recorded_at)
SELECT s.id_sensor, v.reading_value, 'pH', CURRENT_TIMESTAMP - v.hours_ago * INTERVAL '1' HOUR
FROM sensors s
CROSS JOIN (VALUES
        (23, 6.17), (22, 6.26), (21, 6.34), (20, 6.42), (19, 6.48), (18, 6.52),
        (17, 6.54), (16, 6.52), (15, 6.49), (14, 6.43), (13, 6.35), (12, 6.26),
        (11, 6.17), (10, 6.09), (9, 6.01), (8, 5.96), (7, 5.92), (6, 5.91),
        (5, 5.91), (4, 5.93), (3, 5.97), (2, 6.02), (1, 6.08), (0, 6.15)
    ) AS v(hours_ago, reading_value)
WHERE s.sensor_code = 'AS-003'
  AND NOT EXISTS (SELECT 1 FROM sensor_readings r WHERE r.id_sensor = s.id_sensor);

INSERT INTO sensor_readings (id_sensor, reading_value, unit, recorded_at)
SELECT s.id_sensor, v.reading_value, '%', CURRENT_TIMESTAMP - v.hours_ago * INTERVAL '1' HOUR
FROM sensors s
CROSS JOIN (VALUES
        (23, 65.04), (22, 67.25), (21, 69.49), (20, 71.61), (19, 73.42), (18, 74.77),
        (17, 75.51), (16, 75.55), (15, 74.85), (14, 73.42), (13, 71.38), (12, 68.88),
        (11, 66.12), (10, 63.34), (9, 60.77), (8, 58.65), (7, 57.13), (6, 56.33),
        (5, 56.30), (4, 57.00), (3, 58.35), (2, 60.21), (1, 62.40), (0, 64.74)
    ) AS v(hours_ago, reading_value)
WHERE s.sensor_code = 'AS-004'
  AND NOT EXISTS (SELECT 1 FROM sensor_readings r WHERE r.id_sensor = s.id_sensor);

INSERT INTO sensor_readings (id_sensor, reading_value, unit, recorded_at)
SELECT s.id_sensor, v.reading_value, 'dS/m', CURRENT_TIMESTAMP - v.hours_ago * INTERVAL '1' HOUR
FROM sensors s
CROSS JOIN (VALUES
        (23, 1.40), (22, 1.44), (21, 1.48), (20, 1.52), (19, 1.55), (18, 1.57),
        (17, 1.59), (16, 1.59), (15, 1.58), (14, 1.56), (13, 1.52), (12, 1.48),
        (11, 1.42), (10, 1.36), (9, 1.31), (8, 1.25), (7, 1.21), (6, 1.19),
        (5, 1.18), (4, 1.18), (3, 1.21), (2, 1.25), (1, 1.30), (0, 1.36)
    ) AS v(hours_ago, reading_value)
WHERE s.sensor_code = 'AS-005'
  AND NOT EXISTS (SELECT 1 FROM sensor_readings r WHERE r.id_sensor = s.id_sensor);

INSERT INTO alerts (id_crop, id_sensor, alert_type, severity, message, detected_value, acknowledged, created_at)
SELECT c.id_crop, s.id_sensor, v.alert_type, v.severity, v.message, v.detected_value, v.acknowledged,
       CURRENT_TIMESTAMP - v.hours_ago * INTERVAL '1' HOUR
FROM crops c
JOIN estates e ON e.id_estate = c.id_estate
JOIN users u ON u.id_user = e.id_user AND u.email = 'demo@agrosense.co'
JOIN (VALUES
        ('Café', 'AS-001', 'LOW_HUMIDITY', 'HIGH', 'La humedad del suelo está por debajo del mínimo configurado.', 37.4, FALSE, 2),
        ('Plátano', 'AS-004', 'RECOMMENDED_WATERING', 'MEDIUM', 'Se recomienda regar en las próximas horas.', 58.1, FALSE, 5),
        ('Papa', CAST(NULL AS VARCHAR(255)), 'PEST_DETECTED', 'VERY_HIGH', 'Posible presencia de plaga reportada en la parcela.', CAST(NULL AS NUMERIC(12, 4)), FALSE, 9),
        ('Café', 'AS-002', 'HIGH_TEMPERATURE', 'LOW', 'La temperatura superó brevemente el máximo.', 35.6, TRUE, 27)
    ) AS v(crop_name, sensor_code, alert_type, severity, message, detected_value, acknowledged, hours_ago)
    ON v.crop_name = c.name
LEFT JOIN sensors s ON s.sensor_code = v.sensor_code
WHERE NOT EXISTS (SELECT 1 FROM alerts a WHERE a.id_crop = c.id_crop AND a.alert_type = v.alert_type);

INSERT INTO irrigations (id_crop, started_at, ended_at, duration_min, water_liters, irrigation_type, activated_by)
SELECT c.id_crop,
       CURRENT_TIMESTAMP - v.hours_ago * INTERVAL '1' HOUR,
       CURRENT_TIMESTAMP - v.hours_ago * INTERVAL '1' HOUR + v.duration_min * INTERVAL '1' MINUTE,
       v.duration_min, v.water_liters, v.irrigation_type,
       CASE WHEN v.irrigation_type = 'MANUAL' THEN u.id_user END
FROM crops c
JOIN estates e ON e.id_estate = c.id_estate
JOIN users u ON u.id_user = e.id_user AND u.email = 'demo@agrosense.co'
JOIN (VALUES
        ('Café', 27, 30, 450.00, 'AUTOMATIC'),
        ('Plátano', 49, 45, 680.00, 'MANUAL'),
        ('Café', 96, 25, 380.00, 'AUTOMATIC'),
        ('Papa', 126, 20, 240.00, 'MANUAL')
    ) AS v(crop_name, hours_ago, duration_min, water_liters, irrigation_type) ON v.crop_name = c.name
WHERE NOT EXISTS (SELECT 1 FROM irrigations i WHERE i.id_crop = c.id_crop AND i.water_liters = v.water_liters);

INSERT INTO ai_predictions (id_crop, prediction_type, confidence, recommendation, model_used)
SELECT c.id_crop, 'IRRIGATION', v.confidence, v.recommendation, 'sample-data'
FROM crops c
JOIN estates e ON e.id_estate = c.id_estate
JOIN users u ON u.id_user = e.id_user AND u.email = 'demo@agrosense.co'
JOIN (VALUES
        ('Café', 0.8700, 'Regar 30 minutos antes de las 6:00 a. m.'),
        ('Plátano', 0.6400, 'No se requiere riego en las próximas 12 horas.')
    ) AS v(crop_name, confidence, recommendation) ON v.crop_name = c.name
WHERE NOT EXISTS (SELECT 1 FROM ai_predictions p WHERE p.id_crop = c.id_crop);
