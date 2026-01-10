--liquibase formatted sql

--changeset village:add_health_check_failures
ALTER TABLE directory_sites
ADD COLUMN health_check_failures INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN directory_sites.health_check_failures IS 'Consecutive health check failures before marking dead';

--rollback ALTER TABLE directory_sites DROP COLUMN health_check_failures;
