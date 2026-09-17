ALTER TABLE wp_safety_commands
    DROP CONSTRAINT ck_wp_safety_command_type;

ALTER TABLE wp_safety_commands
    ADD CONSTRAINT ck_wp_safety_command_type CHECK (command_type IN
        ('PREVIEW_ACTIVATION','PREVIEW_SCOPE','PREVIEW_CLOSURE',
         'ACTIVATE','REVISE_SCOPE','RESEND','SEND_MESSAGE','RESPOND','CONFIRM_ASSEMBLY',
         'REQUEST_CLOSURE','APPROVE_CLOSURE','CREATE_EXPORT','CONFIGURE_CONNECTOR',
         'CONFIGURE_EMERGENCY_CONTACT','PREVIEW_EMERGENCY_HANDOFF',
         'EMERGENCY_HANDOFF','RECONCILE_EMERGENCY_HANDOFF'));

CREATE TABLE wp_safety_emergency_contacts (
    contact_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    contact_kind VARCHAR(24) NOT NULL,
    display_name_ko VARCHAR(160) NOT NULL,
    display_name_en VARCHAR(160) NOT NULL,
    action_mode VARCHAR(24) NOT NULL,
    tel_uri VARCHAR(32),
    direct_tel_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    connector_kind VARCHAR(24),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    updated_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, contact_id),
    CONSTRAINT ck_wp_safety_emergency_contact_kind CHECK
        (contact_kind IN ('HOTLINE','RADIO','PUBLIC_EMERGENCY')),
    CONSTRAINT ck_wp_safety_emergency_contact_mode CHECK
        (action_mode IN ('TEL_URI','GOVERNED_HANDOFF')),
    CONSTRAINT ck_wp_safety_emergency_contact_tel CHECK (
        (action_mode='TEL_URI' AND tel_uri ~ '^tel:[+][1-9][0-9]{6,14}$'
          AND connector_kind IS NULL)
        OR
        (action_mode='GOVERNED_HANDOFF' AND tel_uri IS NULL
          AND direct_tel_allowed=FALSE AND connector_kind='EMERGENCY_119')),
    CONSTRAINT ck_wp_safety_emergency_contact_labels CHECK
        (length(btrim(display_name_ko)) BETWEEN 1 AND 160
         AND length(btrim(display_name_en)) BETWEEN 1 AND 160),
    CONSTRAINT ck_wp_safety_emergency_contact_sort CHECK (sort_order BETWEEN 0 AND 10000),
    CONSTRAINT ck_wp_safety_emergency_contact_version CHECK (version > 0)
);

CREATE INDEX idx_wp_safety_emergency_contacts_active
    ON wp_safety_emergency_contacts(tenant_id, active, sort_order, contact_id);

CREATE TABLE wp_safety_emergency_handoff_previews (
    handoff_preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    command_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    incident_id UUID NOT NULL,
    contact_id UUID NOT NULL,
    expected_incident_version BIGINT NOT NULL,
    expected_contact_version BIGINT NOT NULL,
    provider_state VARCHAR(32) NOT NULL,
    provider_code VARCHAR(80),
    provider_configuration_version BIGINT,
    provider_evidence_reference VARCHAR(320),
    eligible BOOLEAN NOT NULL,
    limitations JSONB NOT NULL DEFAULT '[]'::jsonb,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, handoff_preview_id),
    UNIQUE (tenant_id, command_id),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_safety_commands(tenant_id, command_id),
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    FOREIGN KEY (tenant_id, contact_id)
        REFERENCES wp_safety_emergency_contacts(tenant_id, contact_id),
    CONSTRAINT ck_wp_safety_emergency_preview_provider_state CHECK
        (provider_state IN ('NOT_CONFIGURED','CONFIGURED_UNVERIFIED','READY','DEGRADED','STALE')),
    CONSTRAINT ck_wp_safety_emergency_preview_versions CHECK
        (expected_incident_version > 0 AND expected_contact_version > 0
         AND (provider_configuration_version IS NULL OR provider_configuration_version > 0)),
    CONSTRAINT ck_wp_safety_emergency_preview_json CHECK
        (jsonb_typeof(limitations)='array'),
    CONSTRAINT ck_wp_safety_emergency_preview_expiry CHECK (expires_at > created_at)
);

CREATE TABLE wp_safety_emergency_handoffs (
    handoff_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    command_id UUID NOT NULL,
    handoff_preview_id UUID NOT NULL,
    incident_id UUID NOT NULL,
    contact_id UUID NOT NULL,
    handoff_state VARCHAR(24) NOT NULL,
    provider_code VARCHAR(80) NOT NULL,
    provider_configuration_version BIGINT NOT NULL,
    provider_operation_reference VARCHAR(320),
    provider_evidence_reference VARCHAR(320),
    result_code VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, handoff_id),
    UNIQUE (tenant_id, command_id),
    UNIQUE (tenant_id, handoff_preview_id),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_safety_commands(tenant_id, command_id),
    FOREIGN KEY (tenant_id, handoff_preview_id)
        REFERENCES wp_safety_emergency_handoff_previews(tenant_id, handoff_preview_id),
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    FOREIGN KEY (tenant_id, contact_id)
        REFERENCES wp_safety_emergency_contacts(tenant_id, contact_id),
    CONSTRAINT ck_wp_safety_emergency_handoff_state CHECK
        (handoff_state IN ('SUCCEEDED','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_safety_emergency_handoff_version CHECK
        (provider_configuration_version > 0 AND version > 0),
    CONSTRAINT ck_wp_safety_emergency_handoff_completion CHECK
        ((handoff_state='RESULT_UNKNOWN' AND completed_at IS NULL)
         OR (handoff_state IN ('SUCCEEDED','FAILED') AND completed_at IS NOT NULL))
);

CREATE INDEX idx_wp_safety_emergency_handoff_recovery
    ON wp_safety_emergency_handoffs(tenant_id, handoff_state, updated_at, handoff_id);

COMMENT ON COLUMN wp_safety_emergency_contacts.tel_uri IS
    'Tenant-approved organizational hotline URI. Personal contact data and provider secrets are prohibited.';
COMMENT ON TABLE wp_safety_emergency_handoffs IS
    'Governed external emergency handoff receipts. Provider credentials remain in the external secret manager.';
