-- Wave 3 installs the Widget Registry control plane in SHADOW mode.  It is deliberately
-- expand-only: the existing Home layout/configuration stores remain authoritative and no
-- Registry decision is injected into rendering or provider reads in this migration.

CREATE TABLE plt_widget_registry_state (
    environment VARCHAR(24) PRIMARY KEY,
    migration_mode VARCHAR(16) NOT NULL DEFAULT 'SHADOW',
    runtime_activation_ready BOOLEAN NOT NULL DEFAULT FALSE,
    registry_revision BIGINT NOT NULL DEFAULT 1,
    policy_revision BIGINT NOT NULL DEFAULT 1,
    safety_revision BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_widget_registry_state_mode
        CHECK (migration_mode IN ('STATIC', 'SHADOW', 'AUTHORITATIVE')),
    CONSTRAINT ck_widget_registry_shadow_interlock
        CHECK (NOT runtime_activation_ready AND migration_mode <> 'AUTHORITATIVE'),
    CONSTRAINT ck_widget_registry_revisions
        CHECK (registry_revision > 0 AND policy_revision > 0 AND safety_revision > 0)
);

INSERT INTO plt_widget_registry_state (environment, migration_mode, runtime_activation_ready)
VALUES ('GLOBAL', 'SHADOW', FALSE)
ON CONFLICT (environment) DO NOTHING;

CREATE TABLE plt_widget_renderer_bindings (
    renderer_binding_id UUID PRIMARY KEY,
    renderer_key VARCHAR(120) NOT NULL UNIQUE,
    kind VARCHAR(16) NOT NULL,
    owner_product_key VARCHAR(120) NOT NULL,
    source_app_resource_key VARCHAR(120) NOT NULL,
    minimum_host_api_version INTEGER NOT NULL,
    maximum_host_api_version INTEGER NOT NULL,
    binding_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    binding_revision VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_widget_renderer_native_only CHECK (kind = 'NATIVE'),
    CONSTRAINT ck_widget_renderer_state CHECK (binding_state IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_widget_renderer_host_api CHECK (
        minimum_host_api_version > 0
        AND maximum_host_api_version >= minimum_host_api_version),
    CONSTRAINT ck_widget_renderer_no_remote_code CHECK (
        renderer_key !~* '(^|[.:/])(https?|javascript|data|remote)([.:/]|$)')
);

CREATE TABLE plt_widget_definitions (
    definition_id UUID PRIMARY KEY,
    definition_key VARCHAR(160) NOT NULL UNIQUE,
    legacy_widget_key VARCHAR(80) UNIQUE,
    owner_product_key VARCHAR(120) NOT NULL,
    owner_team_key VARCHAR(120) NOT NULL,
    risk_tier VARCHAR(16) NOT NULL,
    data_classification VARCHAR(24) NOT NULL,
    definition_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_widget_definition_key CHECK (
        definition_key = lower(trim(definition_key))
        AND definition_key ~ '^[a-z][a-z0-9]*([.-][a-z0-9]+)*$'),
    CONSTRAINT ck_widget_definition_risk CHECK (risk_tier IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_widget_definition_classification CHECK (
        data_classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_widget_definition_state CHECK (definition_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE plt_widget_definition_versions (
    version_id UUID PRIMARY KEY,
    definition_id UUID NOT NULL REFERENCES plt_widget_definitions(definition_id),
    semantic_version VARCHAR(40) NOT NULL,
    manifest JSONB NOT NULL,
    manifest_hash VARCHAR(64) NOT NULL,
    renderer_key VARCHAR(120) NOT NULL REFERENCES plt_widget_renderer_bindings(renderer_key),
    workflow_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    release_state VARCHAR(20) NOT NULL DEFAULT 'UNPUBLISHED',
    safety_state VARCHAR(20) NOT NULL DEFAULT 'CLEAR',
    immutable BOOLEAN NOT NULL DEFAULT FALSE,
    predecessor_version_id UUID REFERENCES plt_widget_definition_versions(version_id),
    replacement_version_id UUID REFERENCES plt_widget_definition_versions(version_id),
    validation_run_id UUID,
    approved_by BIGINT,
    attestation JSONB NOT NULL DEFAULT '{}'::jsonb,
    certification_status VARCHAR(20) NOT NULL DEFAULT 'NOT_RUN',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_widget_definition_semver UNIQUE (definition_id, semantic_version),
    CONSTRAINT uk_widget_definition_manifest UNIQUE (definition_id, manifest_hash),
    CONSTRAINT ck_widget_semver CHECK (
        semantic_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-((0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?(\+([0-9A-Za-z-]+)(\.[0-9A-Za-z-]+)*)?$'),
    CONSTRAINT ck_widget_manifest_object CHECK (jsonb_typeof(manifest) = 'object'),
    CONSTRAINT ck_widget_manifest_hash CHECK (manifest_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_widget_workflow CHECK (
        workflow_state IN ('DRAFT', 'VALIDATED', 'SUBMITTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_widget_release CHECK (
        release_state IN ('UNPUBLISHED', 'PUBLISHED', 'BLOCKED', 'DEPRECATED')),
    CONSTRAINT ck_widget_safety CHECK (safety_state IN ('CLEAR', 'QUARANTINED', 'REVOKED')),
    CONSTRAINT ck_widget_certification CHECK (
        certification_status IN ('NOT_RUN', 'PASS', 'FAIL', 'EXPIRED', 'WAIVED')),
    CONSTRAINT ck_widget_published_immutable CHECK (
        release_state <> 'PUBLISHED' OR (immutable AND workflow_state = 'APPROVED')),
    CONSTRAINT ck_widget_revoked_not_releasable CHECK (
        safety_state <> 'REVOKED' OR release_state IN ('BLOCKED', 'DEPRECATED'))
);

CREATE INDEX idx_widget_versions_definition_created
    ON plt_widget_definition_versions(definition_id, created_at DESC);
CREATE INDEX idx_widget_versions_effective
    ON plt_widget_definition_versions(release_state, safety_state, renderer_key);

CREATE TABLE plt_widget_release_channels (
    release_channel_id UUID PRIMARY KEY,
    definition_id UUID NOT NULL REFERENCES plt_widget_definitions(definition_id),
    channel VARCHAR(16) NOT NULL,
    current_version_id UUID REFERENCES plt_widget_definition_versions(version_id),
    previous_version_id UUID REFERENCES plt_widget_definition_versions(version_id),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_widget_release_channel UNIQUE (definition_id, channel),
    CONSTRAINT ck_widget_release_channel CHECK (channel IN ('STABLE', 'PREVIEW')),
    CONSTRAINT ck_widget_release_versions_distinct CHECK (
        current_version_id IS NULL OR current_version_id IS DISTINCT FROM previous_version_id)
);

CREATE TABLE plt_widget_evidence (
    evidence_id UUID PRIMARY KEY,
    version_id UUID NOT NULL REFERENCES plt_widget_definition_versions(version_id),
    evidence_type VARCHAR(24) NOT NULL,
    evidence_status VARCHAR(16) NOT NULL,
    manifest_hash VARCHAR(64) NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    evidence_sha256 VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ,
    decision_revision BIGINT NOT NULL DEFAULT 1,
    waived_evidence_id UUID REFERENCES plt_widget_evidence(evidence_id),
    tracking_ticket_ref VARCHAR(128),
    reviewed_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_widget_evidence_type CHECK (
        evidence_type IN ('MANIFEST', 'SECURITY', 'PRIVACY', 'A11Y', 'PERFORMANCE', 'LOCALIZATION')),
    CONSTRAINT ck_widget_evidence_status CHECK (
        evidence_status IN ('PASS', 'FAIL', 'WAIVED', 'EXPIRED')),
    CONSTRAINT ck_widget_evidence_hashes CHECK (
        manifest_hash ~ '^[0-9a-f]{64}$' AND evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_widget_evidence_waiver CHECK (
        (evidence_status = 'WAIVED') = (waived_evidence_id IS NOT NULL))
);

CREATE INDEX idx_widget_evidence_version
    ON plt_widget_evidence(version_id, created_at DESC);

CREATE TABLE adm_tenant_widget_policy_revisions (
    policy_revision_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    definition_id UUID NOT NULL REFERENCES plt_widget_definitions(definition_id),
    revision_number BIGINT NOT NULL,
    policy_state VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL,
    selector_type VARCHAR(16) NOT NULL,
    channel VARCHAR(16),
    version_id UUID REFERENCES plt_widget_definition_versions(version_id),
    supported_surface_keys JSONB NOT NULL,
    audience_selector JSONB NOT NULL,
    required_widget BOOLEAN NOT NULL DEFAULT FALSE,
    locked_configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    sharing_policy VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    impact_revision VARCHAR(64),
    predecessor_revision_id UUID REFERENCES adm_tenant_widget_policy_revisions(policy_revision_id),
    reason_code VARCHAR(64) NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    CONSTRAINT uk_tenant_widget_policy_revision UNIQUE (tenant_id, definition_id, revision_number),
    CONSTRAINT ck_tenant_widget_policy_state CHECK (
        policy_state IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED', 'REVOKED')),
    CONSTRAINT ck_tenant_widget_policy_selector CHECK (
        (selector_type = 'CHANNEL' AND channel IN ('STABLE', 'PREVIEW') AND version_id IS NULL)
        OR (selector_type = 'PINNED' AND channel IS NULL AND version_id IS NOT NULL)),
    CONSTRAINT ck_tenant_widget_policy_json CHECK (
        jsonb_typeof(supported_surface_keys) = 'array'
        AND jsonb_typeof(audience_selector) = 'object'
        AND jsonb_typeof(locked_configuration) = 'object'),
    CONSTRAINT ck_tenant_widget_policy_audience_v1 CHECK (
        audience_selector ->> 'schemaVersion' = '1'
        AND audience_selector ->> 'mode' IN ('ALL_ENTITLED', 'ANY_OF', 'ALL_OF')
        AND jsonb_typeof(audience_selector -> 'roleCodes') = 'array'
        AND jsonb_typeof(audience_selector -> 'groupRefs') = 'array'
        AND audience_selector - 'schemaVersion' - 'mode' - 'roleCodes' - 'groupRefs'
            = '{}'::jsonb),
    CONSTRAINT ck_tenant_widget_policy_sharing CHECK (
        sharing_policy IN ('PRIVATE', 'TENANT', 'DISABLED')),
    CONSTRAINT ck_tenant_widget_policy_impact CHECK (
        impact_revision IS NULL OR impact_revision ~ '^[0-9a-f]{64}$')
);

CREATE TABLE adm_tenant_widget_policy_heads (
    policy_head_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    definition_id UUID NOT NULL REFERENCES plt_widget_definitions(definition_id),
    current_revision_id UUID REFERENCES adm_tenant_widget_policy_revisions(policy_revision_id),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_tenant_widget_policy_head UNIQUE (tenant_id, definition_id)
);

CREATE TABLE plt_widget_instances (
    instance_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    user_id BIGINT NOT NULL,
    surface_key VARCHAR(80) NOT NULL,
    definition_id UUID NOT NULL REFERENCES plt_widget_definitions(definition_id),
    definition_version_id UUID NOT NULL REFERENCES plt_widget_definition_versions(version_id),
    legacy_widget_key VARCHAR(80),
    configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    instance_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_widget_instance_configuration CHECK (jsonb_typeof(configuration) = 'object'),
    CONSTRAINT ck_widget_instance_state CHECK (instance_state IN ('ACTIVE', 'BLOCKED', 'REMOVED'))
);

CREATE INDEX idx_widget_instances_impact
    ON plt_widget_instances(definition_id, definition_version_id, tenant_id, instance_state);

CREATE TABLE plt_widget_runtime_controls (
    control_id UUID PRIMARY KEY,
    tenant_id BIGINT REFERENCES sys_service_tenants(tenant_id),
    provider_product_key VARCHAR(120),
    control_scope VARCHAR(24) NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id VARCHAR(160),
    control_state VARCHAR(16) NOT NULL,
    control_revision BIGINT NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    incident_ref VARCHAR(128),
    expires_at TIMESTAMPTZ,
    predecessor_control_id UUID REFERENCES plt_widget_runtime_controls(control_id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    CONSTRAINT ck_widget_control_scope CHECK (
        control_scope IN ('CATALOG_MUTATIONS', 'CATALOG_DISCOVERY', 'RUNTIME_RENDER', 'RUNTIME_ACTION')),
    CONSTRAINT ck_widget_control_target CHECK (
        target_type IN ('GLOBAL', 'PROVIDER', 'TENANT', 'DEFINITION', 'VERSION')),
    CONSTRAINT ck_widget_control_state CHECK (control_state IN ('DISABLED', 'ENABLED')),
    CONSTRAINT ck_widget_control_target_id CHECK (
        (target_type = 'GLOBAL' AND target_id IS NULL)
        OR (target_type <> 'GLOBAL' AND target_id IS NOT NULL)),
    CONSTRAINT ck_widget_control_tenant CHECK (
        (target_type = 'TENANT' AND tenant_id IS NOT NULL)
        OR target_type <> 'TENANT'),
    CONSTRAINT ck_widget_control_provider CHECK (
        (target_type = 'PROVIDER' AND provider_product_key IS NOT NULL)
        OR target_type <> 'PROVIDER')
);

CREATE UNIQUE INDEX uk_widget_runtime_control_active_target
    ON plt_widget_runtime_controls(
        control_scope, target_type,
        coalesce(target_id, ''), coalesce(tenant_id, -1), coalesce(provider_product_key, ''))
    WHERE control_state = 'DISABLED';

CREATE TABLE plt_widget_runtime_enable_approvals (
    approval_id UUID PRIMARY KEY,
    control_id UUID NOT NULL REFERENCES plt_widget_runtime_controls(control_id),
    control_revision BIGINT NOT NULL,
    approval_state VARCHAR(16) NOT NULL,
    evidence_refs JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    approved_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed_at TIMESTAMPTZ,
    consumed_by_command_id UUID,
    CONSTRAINT ck_widget_runtime_approval_state CHECK (
        approval_state IN ('ACTIVE', 'CONSUMED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_widget_runtime_approval_evidence CHECK (jsonb_typeof(evidence_refs) = 'array'),
    CONSTRAINT ck_widget_runtime_approval_consumed CHECK (
        (approval_state = 'CONSUMED') =
        (consumed_at IS NOT NULL AND consumed_by_command_id IS NOT NULL))
);

CREATE INDEX idx_widget_runtime_approval_control
    ON plt_widget_runtime_enable_approvals(control_id, approval_state, expires_at);

CREATE TABLE plt_widget_registry_events (
    event_id UUID PRIMARY KEY,
    registry_revision BIGINT NOT NULL,
    tenant_id BIGINT,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(160) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    command_id UUID,
    actor_id BIGINT NOT NULL,
    correlation_id VARCHAR(128),
    before_snapshot JSONB,
    after_snapshot JSONB,
    evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_widget_registry_event_revision UNIQUE (registry_revision),
    CONSTRAINT ck_widget_event_snapshots CHECK (
        (before_snapshot IS NULL OR jsonb_typeof(before_snapshot) = 'object')
        AND (after_snapshot IS NULL OR jsonb_typeof(after_snapshot) = 'object')
        AND jsonb_typeof(evidence_refs) = 'array')
);

CREATE INDEX idx_widget_registry_events_aggregate
    ON plt_widget_registry_events(aggregate_type, aggregate_id, occurred_at DESC);

CREATE TABLE plt_widget_command_receipts (
    receipt_id UUID PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    command_id UUID NOT NULL,
    operation VARCHAR(80) NOT NULL,
    target_key VARCHAR(200) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    response_type VARCHAR(200) NOT NULL,
    response_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_widget_command_receipt UNIQUE (actor_id, command_id),
    CONSTRAINT ck_widget_command_receipt_hash CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_widget_command_receipt_payload CHECK (jsonb_typeof(response_payload) = 'object')
);

CREATE OR REPLACE FUNCTION reject_immutable_widget_version_changes()
RETURNS trigger AS $$
BEGIN
    IF OLD.immutable AND (
        NEW.definition_id IS DISTINCT FROM OLD.definition_id
        OR NEW.semantic_version IS DISTINCT FROM OLD.semantic_version
        OR NEW.manifest IS DISTINCT FROM OLD.manifest
        OR NEW.manifest_hash IS DISTINCT FROM OLD.manifest_hash
        OR NEW.renderer_key IS DISTINCT FROM OLD.renderer_key
        OR NEW.predecessor_version_id IS DISTINCT FROM OLD.predecessor_version_id
    ) THEN
        RAISE EXCEPTION 'immutable widget version content cannot be changed';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_widget_version_content_immutable
BEFORE UPDATE ON plt_widget_definition_versions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_widget_version_changes();

CREATE OR REPLACE FUNCTION reject_widget_registry_immutable_row_changes()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'widget registry ledger row is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_widget_evidence_immutable
BEFORE UPDATE OR DELETE ON plt_widget_evidence
FOR EACH ROW EXECUTE FUNCTION reject_widget_registry_immutable_row_changes();

CREATE TRIGGER trg_widget_registry_event_immutable
BEFORE UPDATE OR DELETE ON plt_widget_registry_events
FOR EACH ROW EXECUTE FUNCTION reject_widget_registry_immutable_row_changes();

CREATE TRIGGER trg_widget_command_receipt_immutable
BEFORE UPDATE OR DELETE ON plt_widget_command_receipts
FOR EACH ROW EXECUTE FUNCTION reject_widget_registry_immutable_row_changes();

COMMENT ON TABLE plt_widget_registry_state IS
    'Wave 3 registry control-plane readiness. Runtime activation remains permanently false in this release.';
COMMENT ON COLUMN plt_widget_definition_versions.manifest IS
    'Closed WidgetManifestV1 canonical source. Renderer binding contains an allowlisted native key only; URLs and remote JavaScript are forbidden.';
COMMENT ON TABLE plt_widget_registry_events IS
    'Append-only lifecycle evidence ledger. Rollback is represented by a new event/revision.';
