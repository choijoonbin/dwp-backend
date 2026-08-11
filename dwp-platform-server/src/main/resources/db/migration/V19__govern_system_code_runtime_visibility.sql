ALTER TABLE sys_code_sets
    ADD COLUMN runtime_visibility VARCHAR(20) NOT NULL DEFAULT 'ADMIN_ONLY';

ALTER TABLE sys_code_sets
    ADD CONSTRAINT ck_sys_code_sets_runtime_visibility
        CHECK (runtime_visibility IN ('ADMIN_ONLY', 'RUNTIME'));

CREATE OR REPLACE FUNCTION sys_guard_code_set_revision()
RETURNS TRIGGER AS $$
DECLARE
    metadata_changed BOOLEAN;
BEGIN
    IF NEW.schema_version < OLD.schema_version THEN
        RAISE EXCEPTION 'System code schema version cannot decrease'
            USING ERRCODE = '23514';
    END IF;

    metadata_changed := ROW(
        NEW.owner_service, NEW.display_name, NEW.description,
        NEW.configuration_level, NEW.validation_source,
        NEW.source_reference, NEW.lifecycle_state, NEW.contract_kind,
        NEW.runtime_visibility)
        IS DISTINCT FROM ROW(
        OLD.owner_service, OLD.display_name, OLD.description,
        OLD.configuration_level, OLD.validation_source,
        OLD.source_reference, OLD.lifecycle_state, OLD.contract_kind,
        OLD.runtime_visibility);

    IF metadata_changed AND NEW.schema_version = OLD.schema_version THEN
        NEW.schema_version := OLD.schema_version + 1;
    END IF;
    IF metadata_changed OR NEW.schema_version <> OLD.schema_version THEN
        NEW.updated_at := CURRENT_TIMESTAMP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

UPDATE sys_code_sets code_set
   SET runtime_visibility = 'RUNTIME'
 WHERE EXISTS (
       SELECT 1
         FROM sys_code_bindings binding
        WHERE binding.code_set_key = code_set.code_set_key
          AND binding.enforcement_type = 'CATALOG_LOOKUP'
          AND binding.lifecycle_state = 'ACTIVE');

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.SYS_CODE_SETS.RUNTIME_VISIBILITY', 'dwp-platform-server',
     'System code runtime visibility',
     'Exposure policy separating administrator evidence from runtime-safe code values.',
     'SYSTEM', 'CHECK', 'sys_code_sets.runtime_visibility', 'SECURITY');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.SYS_CODE_SETS.RUNTIME_VISIBILITY', 'ADMIN_ONLY', 'Administrator only',
     '{"ko":"관리자 전용","en":"Administrator only"}', 10,
     '{"runtimeReadable":false}'),
    ('PLATFORM.SYS_CODE_SETS.RUNTIME_VISIBILITY', 'RUNTIME', 'Runtime readable',
     '{"ko":"런타임 조회 가능","en":"Runtime readable"}', 20,
     '{"runtimeReadable":true}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.SYS_CODE_SETS.RUNTIME_VISIBILITY', 'dwp-platform-server',
     'DATABASE_COLUMN', 'sys_code_sets.runtime_visibility', 'CHECK');

DROP VIEW sys_code_catalog_health;

CREATE VIEW sys_code_catalog_health AS
SELECT code_set.code_set_key,
       code_set.owner_service,
       code_set.contract_kind,
       code_set.configuration_level,
       code_set.validation_source,
       code_set.runtime_visibility,
       COUNT(DISTINCT code_value.code) AS value_count,
       COUNT(DISTINCT binding.code_binding_id) AS binding_count,
       COUNT(DISTINCT binding.code_binding_id) FILTER (
           WHERE binding.enforcement_type IN (
               'CHECK', 'FOREIGN_KEY', 'CATALOG_LOOKUP', 'TYPED_CONTRACT')) AS enforced_binding_count,
       CASE
           WHEN COUNT(DISTINCT code_value.code) > 0
            AND COUNT(DISTINCT binding.code_binding_id) > 0
            AND COUNT(DISTINCT binding.code_binding_id) =
                COUNT(DISTINCT binding.code_binding_id) FILTER (
                    WHERE binding.enforcement_type IN (
                        'CHECK', 'FOREIGN_KEY', 'CATALOG_LOOKUP', 'TYPED_CONTRACT'))
           THEN 'REGISTERED'
           ELSE 'INCOMPLETE'
       END AS registration_state
  FROM sys_code_sets code_set
  LEFT JOIN sys_code_values code_value
    ON code_value.code_set_key = code_set.code_set_key
   AND code_value.lifecycle_state = 'ACTIVE'
  LEFT JOIN sys_code_bindings binding
    ON binding.code_set_key = code_set.code_set_key
   AND binding.lifecycle_state = 'ACTIVE'
 GROUP BY code_set.code_set_key, code_set.owner_service,
          code_set.contract_kind, code_set.configuration_level,
          code_set.validation_source, code_set.runtime_visibility;

COMMENT ON COLUMN sys_code_sets.runtime_visibility IS
    'ADMIN_ONLY keeps source and values on the administrator surface; RUNTIME allows the reduced catalog projection.';
COMMENT ON VIEW sys_code_catalog_health IS
    'Declared registration, runtime exposure, and enforcement evidence verified by scripts/audit-code-contracts.sh.';
