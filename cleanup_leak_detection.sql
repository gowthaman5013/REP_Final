-- =============================================================================
-- Aquatrac - Water Leakage Detection Removal Migration Script (OPTIONAL)
-- =============================================================================
-- CAUTION:
-- Run this script ONLY after backing up your database and verifying that the
-- application is running properly without the ML leakage detection subsystem.
-- =============================================================================

USE Aquatrac_Final1;

-- 1. Backup tables before dropping (Optional safety step)
-- CREATE TABLE backup_leak_incidents AS SELECT * FROM leak_incidents;
-- CREATE TABLE backup_leak_feedback AS SELECT * FROM leak_feedback;

-- 2. Drop leakage-exclusive tables (in order of FK dependencies)
DROP TABLE IF EXISTS leak_feedback;
DROP TABLE IF EXISTS leak_incidents;

-- 3. Remove leakage-specific columns from water_usage (preserving core usage columns)
ALTER TABLE water_usage
    DROP COLUMN IF EXISTS anomaly_score,
    DROP COLUMN IF EXISTS expected_min,
    DROP COLUMN IF EXISTS expected_max,
    DROP COLUMN IF EXISTS anomaly_reason,
    DROP COLUMN IF EXISTS anomaly_confidence,
    DROP COLUMN IF EXISTS anomaly_pattern,
    DROP COLUMN IF EXISTS data_quality_flag,
    DROP COLUMN IF EXISTS model_version,
    DROP COLUMN IF EXISTS scored_at,
    DROP COLUMN IF EXISTS incident_id;

-- 4. Verification queries
-- DESCRIBE water_usage;
-- SHOW TABLES;

