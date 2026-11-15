-- Migration 005 (2026-11-15): two alert types for the air's relative humidity measured by the ESP32's DHT11,
-- LOW_AIR_HUMIDITY and HIGH_AIR_HUMIDITY, raised against the crop's optional air humidity range (migration 004).
-- Run it AFTER 004 and BEFORE deploying a backend that raises them: without it the CHECK rejects those alerts.
-- Safe to run more than once. schema.sql already has the same list for new databases.

ALTER TABLE alerts DROP CONSTRAINT IF EXISTS ck_alerts_type;

ALTER TABLE alerts ADD CONSTRAINT ck_alerts_type CHECK (alert_type IN
    ('LOW_HUMIDITY', 'HIGH_HUMIDITY', 'HIGH_TEMPERATURE', 'LOW_TEMPERATURE', 'PH_OUT_OF_RANGE',
     'HIGH_CONDUCTIVITY', 'PEST_DETECTED', 'RECOMMENDED_WATERING', 'WATER_STRESS', 'FROST_RISK', 'SENSOR_OFFLINE',
     'SENSOR_FAULT', 'LOW_AIR_HUMIDITY', 'HIGH_AIR_HUMIDITY'));
