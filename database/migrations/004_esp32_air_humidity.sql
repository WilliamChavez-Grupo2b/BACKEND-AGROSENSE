-- Migration 004 (2026-11-15): the range of the air's relative humidity on each crop, and the demo device renamed after
-- the ESP32 that really feeds it. Run it by hand on the production database BEFORE deploying a backend or a frontend
-- that maps the new columns: both validate their tables at start-up. Safe to run more than once. Nothing here deletes data.

ALTER TABLE crops ADD COLUMN IF NOT EXISTS air_humidity_min NUMERIC(5, 2);
ALTER TABLE crops ADD COLUMN IF NOT EXISTS air_humidity_max NUMERIC(5, 2);

ALTER TABLE crops DROP CONSTRAINT IF EXISTS ck_crops_air_humidity;
ALTER TABLE crops ADD CONSTRAINT ck_crops_air_humidity CHECK (air_humidity_min IS NULL OR air_humidity_max IS NULL
    OR air_humidity_min < air_humidity_max);

-- The device was named after the Raspberry Pi it no longer is. After this, set ESP32_DEVICE_ID=ESP32-PLANTICAS (or leave
-- it unset) wherever it still says RPI-DEMO-01.
UPDATE devices SET device_code = 'ESP32-PLANTICAS'
WHERE device_code = 'RPI-DEMO-01'
  AND NOT EXISTS (SELECT 1 FROM devices other WHERE other.device_code = 'ESP32-PLANTICAS');
