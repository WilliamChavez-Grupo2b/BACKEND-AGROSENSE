-- DESTRUCTIVE: drops every AgroSense table and all the data in them.
-- Only for development databases. Run schema.sql afterwards to recreate the empty tables.

DROP TABLE IF EXISTS ai_predictions;
DROP TABLE IF EXISTS irrigations;
DROP TABLE IF EXISTS alerts;
DROP TABLE IF EXISTS sensor_readings;
DROP TABLE IF EXISTS sensors;
DROP TABLE IF EXISTS crops;
DROP TABLE IF EXISTS estates;
DROP TABLE IF EXISTS users;
