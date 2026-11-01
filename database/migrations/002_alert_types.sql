-- Migration 002 (2026-11-01): two new alert types, FROST_RISK and SENSOR_OFFLINE.
-- Run it by hand on the production database BEFORE deploying a backend that raises them: without it the CHECK
-- rejects those alerts. Safe to run more than once. schema.sql already has the same list for new databases.

ALTER TABLE alerts DROP CONSTRAINT IF EXISTS ck_alerts_type;

ALTER TABLE alerts ADD CONSTRAINT ck_alerts_type CHECK (alert_type IN
    ('LOW_HUMIDITY', 'HIGH_HUMIDITY', 'HIGH_TEMPERATURE', 'LOW_TEMPERATURE', 'PH_OUT_OF_RANGE',
     'HIGH_CONDUCTIVITY', 'PEST_DETECTED', 'RECOMMENDED_WATERING', 'WATER_STRESS', 'FROST_RISK', 'SENSOR_OFFLINE'));
