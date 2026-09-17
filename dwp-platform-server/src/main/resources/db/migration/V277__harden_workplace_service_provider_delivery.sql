-- Freeze the provider binding used by reservation-linked service operations.  Provider profile
-- rotation must never redirect an accepted cancellation or access-grant command to a new secret.
ALTER TABLE wp_service_order_lines
    ADD COLUMN provider_credential_binding_reference VARCHAR(320);

UPDATE wp_service_order_lines line
   SET provider_credential_binding_reference = profile.credential_binding_reference
  FROM wp_service_provider_profiles profile
 WHERE profile.tenant_id = line.tenant_id
   AND profile.provider_code = line.provider_code
   AND profile.configuration_version = line.provider_configuration_version;

ALTER TABLE wp_service_line_adjustments
    ADD COLUMN provider_code_snapshot VARCHAR(80),
    ADD COLUMN provider_configuration_version BIGINT,
    ADD COLUMN provider_credential_binding_reference VARCHAR(320),
    ADD COLUMN provider_recovery_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN provider_next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE wp_service_line_adjustments adjustment
   SET provider_code_snapshot = line.provider_code,
       provider_configuration_version = line.provider_configuration_version,
       provider_credential_binding_reference = line.provider_credential_binding_reference
  FROM wp_service_order_lines line
 WHERE line.tenant_id = adjustment.tenant_id
   AND line.service_order_id = adjustment.service_order_id
   AND line.service_order_line_id = adjustment.service_order_line_id;

ALTER TABLE wp_service_line_adjustments
    ALTER COLUMN provider_code_snapshot SET NOT NULL,
    ALTER COLUMN provider_configuration_version SET NOT NULL,
    ADD CONSTRAINT ck_wp_service_line_adjustment_provider_snapshot CHECK
        (provider_code_snapshot ~ '^[A-Za-z0-9._-]{1,80}$'
         AND provider_configuration_version >= 0);

ALTER TABLE wp_service_ephemeral_access_grants
    ADD COLUMN adapter_type_snapshot VARCHAR(80),
    ADD COLUMN provider_configuration_version BIGINT,
    ADD COLUMN provider_credential_binding_reference VARCHAR(320),
    ADD COLUMN provider_operation_kind VARCHAR(16),
    ADD COLUMN provider_operation_command_id UUID,
    ADD COLUMN provider_recovery_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN provider_next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE wp_service_operations_commands
    ADD COLUMN provider_profile_id_snapshot UUID,
    ADD COLUMN provider_code_snapshot VARCHAR(80),
    ADD COLUMN adapter_type_snapshot VARCHAR(80),
    ADD COLUMN provider_configuration_version BIGINT,
    ADD COLUMN provider_credential_binding_reference VARCHAR(320),
    ADD COLUMN provider_capabilities_snapshot JSONB,
    ADD COLUMN provider_profile_version BIGINT;

-- Every existing grant predates immutable provider capture.  Its active profile may have rotated
-- after issuance, regardless of the current grant state, so attributing it to that mutable row
-- would let a later revoke target a different provider owner.  Preserve all legacy grants for
-- manual recovery and never attach current credentials/configuration or an operation command.
UPDATE wp_service_ephemeral_access_grants
   SET adapter_type_snapshot = NULL,
       provider_configuration_version = NULL,
       provider_credential_binding_reference = NULL,
       provider_operation_kind = 'LEGACY_UNKNOWN',
       provider_operation_command_id = NULL;

ALTER TABLE wp_service_operations_commands
    ADD CONSTRAINT uq_wp_service_operations_command_tenant
        UNIQUE (tenant_id, operations_command_id),
    ADD CONSTRAINT ck_wp_service_operations_provider_snapshot CHECK
        ((provider_profile_id_snapshot IS NULL
          AND provider_code_snapshot IS NULL
          AND adapter_type_snapshot IS NULL
          AND provider_configuration_version IS NULL
          AND provider_credential_binding_reference IS NULL
          AND provider_capabilities_snapshot IS NULL
          AND provider_profile_version IS NULL)
         OR (provider_profile_id_snapshot IS NOT NULL
          AND provider_code_snapshot IS NOT NULL
          AND provider_code_snapshot ~ '^[A-Za-z0-9._-]{1,80}$'
          AND adapter_type_snapshot IS NOT NULL
          AND adapter_type_snapshot ~ '^[A-Za-z0-9._-]{1,80}$'
          AND provider_configuration_version IS NOT NULL
          AND provider_configuration_version > 0
          AND provider_credential_binding_reference IS NOT NULL
          AND provider_capabilities_snapshot IS NOT NULL
          AND jsonb_typeof(provider_capabilities_snapshot) = 'array'
          AND provider_profile_version IS NOT NULL
          AND provider_profile_version > 0));

ALTER TABLE wp_service_ephemeral_access_grants
    ALTER COLUMN provider_operation_kind SET NOT NULL,
    ADD CONSTRAINT fk_wp_service_access_grant_operation_command
        FOREIGN KEY (tenant_id, provider_operation_command_id)
        REFERENCES wp_service_operations_commands(tenant_id, operations_command_id),
    ADD CONSTRAINT ck_wp_service_access_grant_provider_snapshot CHECK
        ((provider_operation_kind = 'LEGACY_UNKNOWN'
          AND adapter_type_snapshot IS NULL
          AND provider_configuration_version IS NULL
          AND provider_credential_binding_reference IS NULL
          AND provider_operation_command_id IS NULL)
         OR (provider_operation_kind IN ('ISSUE', 'REVOKE')
          AND adapter_type_snapshot IS NOT NULL
          AND adapter_type_snapshot ~ '^[A-Za-z0-9._-]{1,80}$'
          AND provider_configuration_version IS NOT NULL
          AND provider_configuration_version > 0
          AND provider_credential_binding_reference IS NOT NULL
          AND (grant_state <> 'RESULT_UNKNOWN'
               OR provider_operation_command_id IS NOT NULL))),
    ADD CONSTRAINT ck_wp_service_access_grant_recovery_attempts CHECK
        (provider_recovery_attempt_count >= 0);

ALTER TABLE wp_service_line_adjustments
    ADD CONSTRAINT ck_wp_service_line_adjustment_recovery_attempts CHECK
        (provider_recovery_attempt_count >= 0);

CREATE INDEX idx_wp_service_access_grant_recovery
    ON wp_service_ephemeral_access_grants(
        provider_next_attempt_at, tenant_id, access_grant_id)
    WHERE grant_state = 'RESULT_UNKNOWN' AND provider_operation_kind = 'REVOKE';

CREATE INDEX idx_wp_service_line_adjustment_recovery
    ON wp_service_line_adjustments(
        provider_next_attempt_at, tenant_id, line_adjustment_id)
    WHERE adjustment_state IN ('CANCELLATION_PENDING', 'RECONCILIATION_PENDING',
        'RESULT_UNKNOWN');

COMMENT ON COLUMN wp_service_order_lines.provider_credential_binding_reference IS
    'Opaque provider credential reference captured with the service-order line; never raw secret material.';
COMMENT ON COLUMN wp_service_line_adjustments.provider_credential_binding_reference IS
    'Immutable opaque credential reference used for cancellation status recovery after provider rotation.';
COMMENT ON COLUMN wp_service_ephemeral_access_grants.provider_operation_command_id IS
    'Command owning the current stable provider operation. Recovery performs status lookup only.';
COMMENT ON COLUMN wp_service_ephemeral_access_grants.provider_operation_kind IS
    'ISSUE/REVOKE for immutable snapshots; LEGACY_UNKNOWN marks every pre-snapshot grant and is fail-closed/manual-only.';
