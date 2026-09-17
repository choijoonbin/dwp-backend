ALTER TABLE wp_navigation_device_commands
    ADD COLUMN provider_code VARCHAR(80),
    ADD COLUMN provider_configuration_version BIGINT,
    ADD COLUMN credential_reference VARCHAR(160);

-- A pre-upgrade in-flight command has no immutable provider/configuration/credential snapshot.
-- Its mutation may already have reached the provider, so it must never be redispatched or
-- inferred as failed. Reconciliation remains fail-closed until an operator can establish truth.
UPDATE wp_navigation_device_commands
   SET command_state='RESULT_UNKNOWN',
       result_code=COALESCE(result_code, 'LEGACY_PROVIDER_BINDING_MISSING'),
       completed_at=NULL,
       version=version+1,
       updated_at=CURRENT_TIMESTAMP
 WHERE provider_code IS NULL
   AND provider_configuration_version IS NULL
   AND credential_reference IS NULL
   AND command_state IN ('ACCEPTED','RUNNING','RESULT_UNKNOWN');

UPDATE wp_navigation_device_outbox outbox
   SET delivery_state='RESULT_UNKNOWN',
       next_attempt_at=GREATEST(outbox.next_attempt_at, CURRENT_TIMESTAMP),
       updated_at=CURRENT_TIMESTAMP
  FROM wp_navigation_device_commands command
 WHERE command.tenant_id=outbox.tenant_id
   AND command.command_id=outbox.command_id
   AND command.provider_code IS NULL
   AND command.provider_configuration_version IS NULL
   AND command.credential_reference IS NULL
   AND command.command_state='RESULT_UNKNOWN'
   AND outbox.delivery_state IN ('PENDING','PROCESSING','RETRY','RESULT_UNKNOWN');

ALTER TABLE wp_navigation_device_commands
    ADD CONSTRAINT ck_wp_navigation_command_provider_snapshot CHECK (
        (provider_code IS NULL AND provider_configuration_version IS NULL
            AND credential_reference IS NULL)
        OR
        (provider_code ~ '^[A-Za-z0-9._-]{1,80}$'
            AND provider_configuration_version > 0
            AND credential_reference ~ '^secret-manager://[A-Za-z0-9._/-]{1,143}$')
    );

CREATE INDEX idx_wp_navigation_device_command_recovery
    ON wp_navigation_device_commands(tenant_id, updated_at, command_id)
    WHERE command_state='RESULT_UNKNOWN';

COMMENT ON COLUMN wp_navigation_device_commands.provider_code IS
    'Immutable provider code captured before the durable command is accepted.';
COMMENT ON COLUMN wp_navigation_device_commands.provider_configuration_version IS
    'Immutable provider configuration version used by dispatch and all later GET reconciliation.';
COMMENT ON COLUMN wp_navigation_device_commands.credential_reference IS
    'Opaque secret-manager reference only. Raw provider credentials are never persisted.';
