-- BASE-TFR-SYS-008: Platform-owned HRIS integration control plane.
--
-- The leased surface creates exactly six tenant-owned tables. Command
-- receipts, domain events, audit evidence and operational exceptions continue
-- to use the existing shared Platform stores. Only opaque references and
-- canonical digests are persisted; no secret value, mapping body, provider
-- payload or employee item identity is stored here.

CREATE TABLE sys_connector_definitions (
    tenant_id BIGINT NOT NULL,
    definition_id UUID NOT NULL DEFAULT gen_random_uuid(),
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    connector_key VARCHAR(64) NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'INACTIVE',
    active_version_number BIGINT,
    aggregate_revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, definition_id),
    CONSTRAINT uq_sys_connector_definition_public
        UNIQUE (tenant_id, public_id),
    CONSTRAINT uq_sys_connector_definition_key
        UNIQUE (tenant_id, connector_key),
    CONSTRAINT uq_sys_connector_definition_version_pointer
        UNIQUE (tenant_id, definition_id, active_version_number),
    CONSTRAINT ck_sys_connector_definition_tenant
        CHECK (tenant_id > 0),
    CONSTRAINT ck_sys_connector_definition_key
        CHECK (connector_key ~ '^[a-z][a-z0-9.-]{2,63}$'),
    CONSTRAINT ck_sys_connector_definition_state
        CHECK (lifecycle_state IN (
            'INACTIVE', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_sys_connector_definition_revision
        CHECK (aggregate_revision BETWEEN 1 AND 9007199254740991
            AND (active_version_number IS NULL OR active_version_number
                BETWEEN 1 AND 9007199254740991)),
    CONSTRAINT ck_sys_connector_definition_active_pointer
        CHECK ((lifecycle_state = 'INACTIVE' AND active_version_number IS NULL)
            OR (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED')
                AND active_version_number IS NOT NULL))
);

CREATE TABLE sys_connector_definition_versions (
    tenant_id BIGINT NOT NULL,
    definition_version_id UUID NOT NULL DEFAULT gen_random_uuid(),
    definition_id UUID NOT NULL,
    version_number BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    connector_type VARCHAR(32) NOT NULL,
    endpoint_uri VARCHAR(2048) NOT NULL,
    endpoint_policy_key VARCHAR(128) NOT NULL,
    endpoint_policy_revision BIGINT NOT NULL,
    secret_ref VARCHAR(136) NOT NULL,
    secret_revision BIGINT NOT NULL,
    definition_digest CHAR(64) NOT NULL,
    validation_receipt_id UUID,
    validation_evidence_digest CHAR(64),
    validated_by_actor_id BIGINT,
    validated_at TIMESTAMPTZ,
    connection_test_receipt_id UUID,
    connection_test_result_ref VARCHAR(512),
    connection_test_result_digest CHAR(64),
    connection_test_evidence_digest CHAR(64),
    tested_by_actor_id BIGINT,
    tested_at TIMESTAMPTZ,
    submission_receipt_id UUID,
    submission_evidence_digest CHAR(64),
    submitted_by_actor_id BIGINT,
    submitted_at TIMESTAMPTZ,
    approval_receipt_id UUID,
    approval_evidence_digest CHAR(64),
    approval_step_up_evidence_digest CHAR(64),
    approved_by_actor_id BIGINT,
    approved_at TIMESTAMPTZ,
    activation_receipt_id UUID,
    activation_evidence_digest CHAR(64),
    activation_step_up_evidence_digest CHAR(64),
    activated_by_actor_id BIGINT,
    activated_at TIMESTAMPTZ,
    subject_revision BIGINT NOT NULL DEFAULT 1,
    object_revision BIGINT NOT NULL DEFAULT 1,
    author_actor_id BIGINT NOT NULL,
    author_scope_digest CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, definition_version_id),
    CONSTRAINT uq_sys_connector_definition_version
        UNIQUE (tenant_id, definition_id, version_number),
    CONSTRAINT uq_sys_connector_definition_version_composite
        UNIQUE (
            tenant_id, definition_id, definition_version_id, version_number),
    CONSTRAINT fk_sys_connector_definition_version_definition
        FOREIGN KEY (tenant_id, definition_id)
        REFERENCES sys_connector_definitions (tenant_id, definition_id),
    CONSTRAINT ck_sys_connector_definition_version_numbers
        CHECK (tenant_id > 0
            AND version_number BETWEEN 1 AND 9007199254740991
            AND endpoint_policy_revision BETWEEN 1 AND 9007199254740991
            AND secret_revision BETWEEN 1 AND 9007199254740991
            AND subject_revision BETWEEN 1 AND 9007199254740991
            AND object_revision BETWEEN 1 AND 9007199254740991
            AND author_actor_id BETWEEN 1 AND 9007199254740991
            AND (validated_by_actor_id IS NULL OR validated_by_actor_id
                BETWEEN 1 AND 9007199254740991)
            AND (tested_by_actor_id IS NULL OR tested_by_actor_id
                BETWEEN 1 AND 9007199254740991)
            AND (submitted_by_actor_id IS NULL OR submitted_by_actor_id
                BETWEEN 1 AND 9007199254740991)
            AND (approved_by_actor_id IS NULL OR approved_by_actor_id
                BETWEEN 1 AND 9007199254740991)
            AND (activated_by_actor_id IS NULL OR activated_by_actor_id
                BETWEEN 1 AND 9007199254740991)),
    CONSTRAINT ck_sys_connector_definition_version_state
        CHECK (lifecycle_state IN (
            'DRAFT', 'VALIDATED', 'TESTED', 'PENDING_APPROVAL',
            'APPROVED', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_sys_connector_definition_version_type
        CHECK (connector_type IN (
            'HTTPS', 'SFTP', 'OBJECT_STORE', 'ERP', 'BANK',
            'STATUTORY_PROVIDER')),
    CONSTRAINT ck_sys_connector_definition_version_endpoint
        CHECK (length(endpoint_uri) BETWEEN 1 AND 2048
            AND endpoint_uri ~ '^(https|sftp)://'
            AND endpoint_uri !~ '[?#]'
            AND endpoint_policy_key ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_sys_connector_definition_version_secret_ref
        CHECK (secret_ref ~ '^secret_[A-Za-z0-9_-]{16,128}$'),
    CONSTRAINT ck_sys_connector_definition_version_digest
        CHECK (definition_digest ~ '^[a-f0-9]{64}$'
            AND author_scope_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_sys_connector_definition_validation_evidence
        CHECK ((validation_receipt_id IS NULL
                AND validation_evidence_digest IS NULL
                AND validated_by_actor_id IS NULL AND validated_at IS NULL)
            OR (validation_receipt_id IS NOT NULL
                AND validation_evidence_digest IS NOT NULL
                AND validation_evidence_digest ~ '^[a-f0-9]{64}$'
                AND validated_by_actor_id IS NOT NULL
                AND validated_at IS NOT NULL)),
    CONSTRAINT ck_sys_connector_definition_test_evidence
        CHECK ((connection_test_receipt_id IS NULL
                AND connection_test_result_ref IS NULL
                AND connection_test_result_digest IS NULL
                AND connection_test_evidence_digest IS NULL
                AND tested_by_actor_id IS NULL AND tested_at IS NULL)
            OR (connection_test_receipt_id IS NOT NULL
                AND connection_test_result_ref IS NOT NULL
                AND length(connection_test_result_ref) BETWEEN 1 AND 512
                AND connection_test_result_ref
                    ~ '^[A-Za-z0-9][A-Za-z0-9:/._-]*$'
                AND connection_test_result_ref !~ '://|[?#]'
                AND connection_test_result_digest IS NOT NULL
                AND connection_test_result_digest ~ '^[a-f0-9]{64}$'
                AND connection_test_evidence_digest IS NOT NULL
                AND connection_test_evidence_digest ~ '^[a-f0-9]{64}$'
                AND tested_by_actor_id IS NOT NULL AND tested_at IS NOT NULL)),
    CONSTRAINT ck_sys_connector_definition_submission_evidence
        CHECK ((submission_receipt_id IS NULL
                AND submission_evidence_digest IS NULL
                AND submitted_by_actor_id IS NULL AND submitted_at IS NULL)
            OR (submission_receipt_id IS NOT NULL
                AND submission_evidence_digest IS NOT NULL
                AND submission_evidence_digest ~ '^[a-f0-9]{64}$'
                AND submitted_by_actor_id IS NOT NULL
                AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_sys_connector_definition_approval_evidence
        CHECK ((approval_receipt_id IS NULL
                AND approval_evidence_digest IS NULL
                AND approval_step_up_evidence_digest IS NULL
                AND approved_by_actor_id IS NULL AND approved_at IS NULL)
            OR (approval_receipt_id IS NOT NULL
                AND approval_evidence_digest IS NOT NULL
                AND approval_evidence_digest ~ '^[a-f0-9]{64}$'
                AND approval_step_up_evidence_digest IS NOT NULL
                AND approval_step_up_evidence_digest ~ '^[a-f0-9]{64}$'
                AND approved_by_actor_id IS NOT NULL
                AND approved_at IS NOT NULL
                AND approved_by_actor_id <> author_actor_id)),
    CONSTRAINT ck_sys_connector_definition_activation_evidence
        CHECK ((activation_receipt_id IS NULL
                AND activation_evidence_digest IS NULL
                AND activation_step_up_evidence_digest IS NULL
                AND activated_by_actor_id IS NULL AND activated_at IS NULL)
            OR (activation_receipt_id IS NOT NULL
                AND activation_evidence_digest IS NOT NULL
                AND activation_evidence_digest ~ '^[a-f0-9]{64}$'
                AND activation_step_up_evidence_digest IS NOT NULL
                AND activation_step_up_evidence_digest ~ '^[a-f0-9]{64}$'
                AND activated_by_actor_id IS NOT NULL
                AND activated_at IS NOT NULL
                AND activated_by_actor_id <> author_actor_id)),
    CONSTRAINT ck_sys_connector_definition_state_evidence
        CHECK ((lifecycle_state = 'DRAFT')
            OR (lifecycle_state = 'VALIDATED'
                AND validation_receipt_id IS NOT NULL)
            OR (lifecycle_state = 'TESTED'
                AND validation_receipt_id IS NOT NULL
                AND connection_test_receipt_id IS NOT NULL)
            OR (lifecycle_state = 'PENDING_APPROVAL'
                AND validation_receipt_id IS NOT NULL
                AND connection_test_receipt_id IS NOT NULL
                AND submission_receipt_id IS NOT NULL)
            OR (lifecycle_state = 'APPROVED'
                AND validation_receipt_id IS NOT NULL
                AND connection_test_receipt_id IS NOT NULL
                AND submission_receipt_id IS NOT NULL
                AND approval_receipt_id IS NOT NULL)
            OR (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED')
                AND validation_receipt_id IS NOT NULL
                AND connection_test_receipt_id IS NOT NULL
                AND submission_receipt_id IS NOT NULL
                AND approval_receipt_id IS NOT NULL
                AND activation_receipt_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_sys_connector_one_active_definition_version
    ON sys_connector_definition_versions (tenant_id, definition_id)
    WHERE lifecycle_state = 'ACTIVE';

ALTER TABLE sys_connector_definitions
    ADD CONSTRAINT fk_sys_connector_definition_active_version
    FOREIGN KEY (tenant_id, definition_id, active_version_number)
    REFERENCES sys_connector_definition_versions
        (tenant_id, definition_id, version_number)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE sys_connector_mapping_versions (
    tenant_id BIGINT NOT NULL,
    mapping_version_id UUID NOT NULL DEFAULT gen_random_uuid(),
    definition_id UUID NOT NULL,
    mapping_key VARCHAR(64) NOT NULL,
    version_number BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    direction VARCHAR(8) NOT NULL,
    source_schema_version BIGINT NOT NULL,
    source_schema_digest CHAR(64) NOT NULL,
    target_schema_version BIGINT NOT NULL,
    target_schema_digest CHAR(64) NOT NULL,
    mapping_ref VARCHAR(244) NOT NULL,
    mapping_digest CHAR(64) NOT NULL,
    compatibility_mode VARCHAR(16) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    validation_receipt_id UUID,
    validation_evidence_digest CHAR(64),
    validated_by_actor_id BIGINT,
    validated_at TIMESTAMPTZ,
    dry_run_receipt_id UUID,
    dry_run_result_ref VARCHAR(512),
    dry_run_result_digest CHAR(64),
    dry_run_evidence_digest CHAR(64),
    dry_run_source_count INTEGER,
    dry_run_rejected_count INTEGER,
    dry_run_by_actor_id BIGINT,
    dry_run_at TIMESTAMPTZ,
    submission_receipt_id UUID,
    submission_evidence_digest CHAR(64),
    submitted_by_actor_id BIGINT,
    submitted_at TIMESTAMPTZ,
    approval_receipt_id UUID,
    approval_evidence_digest CHAR(64),
    approval_step_up_evidence_digest CHAR(64),
    approved_by_actor_id BIGINT,
    approved_at TIMESTAMPTZ,
    activation_receipt_id UUID,
    activation_evidence_digest CHAR(64),
    activation_step_up_evidence_digest CHAR(64),
    activated_by_actor_id BIGINT,
    activated_at TIMESTAMPTZ,
    subject_revision BIGINT NOT NULL DEFAULT 1,
    object_revision BIGINT NOT NULL DEFAULT 1,
    author_actor_id BIGINT NOT NULL,
    author_scope_digest CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, mapping_version_id),
    CONSTRAINT uq_sys_connector_mapping_version
        UNIQUE (tenant_id, definition_id, mapping_key, version_number),
    CONSTRAINT uq_sys_connector_mapping_version_composite
        UNIQUE (
            tenant_id, definition_id, mapping_version_id, version_number),
    CONSTRAINT fk_sys_connector_mapping_definition
        FOREIGN KEY (tenant_id, definition_id)
        REFERENCES sys_connector_definitions (tenant_id, definition_id),
    CONSTRAINT ck_sys_connector_mapping_numbers
        CHECK (tenant_id > 0
            AND version_number BETWEEN 1 AND 9007199254740991
            AND source_schema_version BETWEEN 1 AND 9007199254740991
            AND target_schema_version BETWEEN 1 AND 9007199254740991
            AND subject_revision BETWEEN 1 AND 9007199254740991
            AND object_revision BETWEEN 1 AND 9007199254740991
            AND author_actor_id BETWEEN 1 AND 9007199254740991
            AND (validated_by_actor_id IS NULL OR validated_by_actor_id
                BETWEEN 1 AND 9007199254740991)
            AND (dry_run_by_actor_id IS NULL OR dry_run_by_actor_id
                BETWEEN 1 AND 9007199254740991)
            AND (submitted_by_actor_id IS NULL OR submitted_by_actor_id
                BETWEEN 1 AND 9007199254740991)
            AND (approved_by_actor_id IS NULL OR approved_by_actor_id
                BETWEEN 1 AND 9007199254740991)
            AND (activated_by_actor_id IS NULL OR activated_by_actor_id
                BETWEEN 1 AND 9007199254740991)),
    CONSTRAINT ck_sys_connector_mapping_key
        CHECK (mapping_key ~ '^[a-z][a-z0-9.-]{2,63}$'),
    CONSTRAINT ck_sys_connector_mapping_state
        CHECK (lifecycle_state IN (
            'DRAFT', 'VALIDATED', 'PENDING_APPROVAL', 'APPROVED',
            'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_sys_connector_mapping_direction
        CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_sys_connector_mapping_ref
        CHECK (mapping_ref ~ '^ref_[A-Za-z0-9_-]{16,240}$'),
    CONSTRAINT ck_sys_connector_mapping_digests
        CHECK (source_schema_digest ~ '^[a-f0-9]{64}$'
            AND target_schema_digest ~ '^[a-f0-9]{64}$'
            AND mapping_digest ~ '^[a-f0-9]{64}$'
            AND author_scope_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_sys_connector_mapping_compatibility
        CHECK (compatibility_mode IN ('BACKWARD', 'FORWARD', 'FULL', 'EXACT')),
    CONSTRAINT ck_sys_connector_mapping_effective_period
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_sys_connector_mapping_validation_evidence
        CHECK ((validation_receipt_id IS NULL
                AND validation_evidence_digest IS NULL
                AND validated_by_actor_id IS NULL AND validated_at IS NULL)
            OR (validation_receipt_id IS NOT NULL
                AND validation_evidence_digest IS NOT NULL
                AND validation_evidence_digest ~ '^[a-f0-9]{64}$'
                AND validated_by_actor_id IS NOT NULL
                AND validated_at IS NOT NULL)),
    CONSTRAINT ck_sys_connector_mapping_dry_run_evidence
        CHECK ((dry_run_receipt_id IS NULL
                AND dry_run_result_ref IS NULL
                AND dry_run_result_digest IS NULL
                AND dry_run_evidence_digest IS NULL
                AND dry_run_source_count IS NULL
                AND dry_run_rejected_count IS NULL
                AND dry_run_by_actor_id IS NULL AND dry_run_at IS NULL)
            OR (dry_run_receipt_id IS NOT NULL
                AND dry_run_result_ref IS NOT NULL
                AND length(dry_run_result_ref) BETWEEN 1 AND 512
                AND dry_run_result_ref
                    ~ '^[A-Za-z0-9][A-Za-z0-9:/._-]*$'
                AND dry_run_result_ref !~ '://|[?#]'
                AND dry_run_result_digest IS NOT NULL
                AND dry_run_result_digest ~ '^[a-f0-9]{64}$'
                AND dry_run_evidence_digest IS NOT NULL
                AND dry_run_evidence_digest ~ '^[a-f0-9]{64}$'
                AND dry_run_source_count IS NOT NULL
                AND dry_run_source_count BETWEEN 0 AND 1000
                AND dry_run_rejected_count IS NOT NULL
                AND dry_run_rejected_count BETWEEN 0 AND dry_run_source_count
                AND dry_run_by_actor_id IS NOT NULL
                AND dry_run_at IS NOT NULL)),
    CONSTRAINT ck_sys_connector_mapping_submission_evidence
        CHECK ((submission_receipt_id IS NULL
                AND submission_evidence_digest IS NULL
                AND submitted_by_actor_id IS NULL AND submitted_at IS NULL)
            OR (submission_receipt_id IS NOT NULL
                AND submission_evidence_digest IS NOT NULL
                AND submission_evidence_digest ~ '^[a-f0-9]{64}$'
                AND submitted_by_actor_id IS NOT NULL
                AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_sys_connector_mapping_approval_evidence
        CHECK ((approval_receipt_id IS NULL
                AND approval_evidence_digest IS NULL
                AND approval_step_up_evidence_digest IS NULL
                AND approved_by_actor_id IS NULL AND approved_at IS NULL)
            OR (approval_receipt_id IS NOT NULL
                AND approval_evidence_digest IS NOT NULL
                AND approval_evidence_digest ~ '^[a-f0-9]{64}$'
                AND approval_step_up_evidence_digest IS NOT NULL
                AND approval_step_up_evidence_digest ~ '^[a-f0-9]{64}$'
                AND approved_by_actor_id IS NOT NULL
                AND approved_at IS NOT NULL
                AND approved_by_actor_id <> author_actor_id)),
    CONSTRAINT ck_sys_connector_mapping_activation_evidence
        CHECK ((activation_receipt_id IS NULL
                AND activation_evidence_digest IS NULL
                AND activation_step_up_evidence_digest IS NULL
                AND activated_by_actor_id IS NULL AND activated_at IS NULL)
            OR (activation_receipt_id IS NOT NULL
                AND activation_evidence_digest IS NOT NULL
                AND activation_evidence_digest ~ '^[a-f0-9]{64}$'
                AND activation_step_up_evidence_digest IS NOT NULL
                AND activation_step_up_evidence_digest ~ '^[a-f0-9]{64}$'
                AND activated_by_actor_id IS NOT NULL
                AND activated_at IS NOT NULL
                AND activated_by_actor_id <> author_actor_id)),
    CONSTRAINT ck_sys_connector_mapping_state_evidence
        CHECK ((lifecycle_state = 'DRAFT')
            OR (lifecycle_state = 'VALIDATED'
                AND validation_receipt_id IS NOT NULL)
            OR (lifecycle_state = 'PENDING_APPROVAL'
                AND validation_receipt_id IS NOT NULL
                AND dry_run_receipt_id IS NOT NULL
                AND submission_receipt_id IS NOT NULL)
            OR (lifecycle_state = 'APPROVED'
                AND validation_receipt_id IS NOT NULL
                AND dry_run_receipt_id IS NOT NULL
                AND submission_receipt_id IS NOT NULL
                AND approval_receipt_id IS NOT NULL)
            OR (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED')
                AND validation_receipt_id IS NOT NULL
                AND dry_run_receipt_id IS NOT NULL
                AND submission_receipt_id IS NOT NULL
                AND approval_receipt_id IS NOT NULL
                AND activation_receipt_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_sys_connector_one_active_mapping_version
    ON sys_connector_mapping_versions
        (tenant_id, definition_id, mapping_key)
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE sys_connector_executions (
    tenant_id BIGINT NOT NULL,
    execution_id UUID NOT NULL DEFAULT gen_random_uuid(),
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    definition_id UUID NOT NULL,
    definition_version_id UUID NOT NULL,
    definition_version_number BIGINT NOT NULL,
    mapping_version_id UUID NOT NULL,
    mapping_version_number BIGINT NOT NULL,
    endpoint_policy_revision BIGINT NOT NULL,
    secret_revision BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'ACCEPTED',
    payload_ref VARCHAR(256) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    source_count INTEGER NOT NULL,
    source_item_set_digest CHAR(64) NOT NULL,
    selected_item_count INTEGER NOT NULL,
    selected_item_digest CHAR(64) NOT NULL,
    accepted_count INTEGER NOT NULL DEFAULT 0,
    rejected_count INTEGER NOT NULL DEFAULT 0,
    quarantined_count INTEGER NOT NULL DEFAULT 0,
    provider_key VARCHAR(128) NOT NULL,
    provider_operation VARCHAR(64) NOT NULL,
    provider_idempotency_key VARCHAR(160) NOT NULL,
    provider_request_digest CHAR(64) NOT NULL,
    provider_receipt_ref VARCHAR(244),
    provider_receipt_digest CHAR(64),
    provider_evidence_digest CHAR(64),
    provider_failure_conclusive BOOLEAN NOT NULL DEFAULT FALSE,
    unresolved_external_effect BOOLEAN NOT NULL DEFAULT FALSE,
    reconciliation_receipt_ref VARCHAR(244),
    reconciliation_receipt_digest CHAR(64),
    reconciliation_digest CHAR(64),
    compensation_policy_ref VARCHAR(207),
    compensation_policy_digest CHAR(64),
    compensation_receipt_ref VARCHAR(244),
    compensation_receipt_digest CHAR(64),
    operations_exception_ref VARCHAR(512),
    authorization_revision BIGINT NOT NULL,
    population_kind VARCHAR(16) NOT NULL,
    legal_entity_public_id UUID,
    original_command_receipt_id UUID NOT NULL,
    retry_generation INTEGER NOT NULL DEFAULT 0,
    current_attempt_fence BIGINT NOT NULL DEFAULT 0,
    object_revision BIGINT NOT NULL DEFAULT 1,
    requested_by_actor_id BIGINT NOT NULL,
    requested_scope_digest CHAR(64) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, execution_id),
    CONSTRAINT uq_sys_connector_execution_public
        UNIQUE (tenant_id, public_id),
    CONSTRAINT uq_sys_connector_provider_operation_key
        UNIQUE (
            tenant_id, definition_id, provider_operation,
            provider_idempotency_key),
    CONSTRAINT uq_sys_connector_execution_composite
        UNIQUE (tenant_id, execution_id, definition_id),
    CONSTRAINT fk_sys_connector_execution_definition
        FOREIGN KEY (tenant_id, definition_id)
        REFERENCES sys_connector_definitions (tenant_id, definition_id),
    CONSTRAINT fk_sys_connector_execution_definition_version
        FOREIGN KEY (
            tenant_id, definition_id, definition_version_id,
            definition_version_number)
        REFERENCES sys_connector_definition_versions (
            tenant_id, definition_id, definition_version_id, version_number),
    CONSTRAINT fk_sys_connector_execution_mapping_version
        FOREIGN KEY (
            tenant_id, definition_id, mapping_version_id,
            mapping_version_number)
        REFERENCES sys_connector_mapping_versions (
            tenant_id, definition_id, mapping_version_id, version_number),
    CONSTRAINT ck_sys_connector_execution_numbers
        CHECK (tenant_id > 0
            AND definition_version_number BETWEEN 1 AND 9007199254740991
            AND mapping_version_number BETWEEN 1 AND 9007199254740991
            AND endpoint_policy_revision BETWEEN 1 AND 9007199254740991
            AND secret_revision BETWEEN 1 AND 9007199254740991
            AND authorization_revision BETWEEN 1 AND 9007199254740991
            AND requested_by_actor_id BETWEEN 1 AND 9007199254740991
            AND object_revision BETWEEN 1 AND 9007199254740991
            AND current_attempt_fence BETWEEN 0 AND 9007199254740991
            AND retry_generation BETWEEN 0 AND 10000),
    CONSTRAINT ck_sys_connector_execution_operation
        CHECK (operation IN ('IMPORT', 'EXPORT', 'DISPATCH', 'RECONCILE')),
    CONSTRAINT ck_sys_connector_execution_population
        CHECK ((population_kind = 'TENANT'
                    AND legal_entity_public_id IS NULL)
            OR (population_kind = 'LEGAL_ENTITY'
                    AND legal_entity_public_id IS NOT NULL)),
    CONSTRAINT ck_sys_connector_execution_state
        CHECK (lifecycle_state IN (
            'ACCEPTED', 'TRANSFORMING', 'DISPATCHED', 'RESULT_UNKNOWN',
            'ACKNOWLEDGED', 'RECONCILING', 'RECONCILED', 'PARTIAL',
            'FAILED', 'DEAD_LETTER', 'CANCELLED')),
    CONSTRAINT ck_sys_connector_execution_payload_ref
        CHECK (length(payload_ref) BETWEEN 1 AND 256
            AND payload_ref ~ '^[A-Za-z0-9][A-Za-z0-9:/._-]*$'
            AND payload_ref !~ '://|[?#]'),
    CONSTRAINT ck_sys_connector_execution_digests
        CHECK (payload_digest ~ '^[a-f0-9]{64}$'
            AND source_item_set_digest ~ '^[a-f0-9]{64}$'
            AND selected_item_digest ~ '^[a-f0-9]{64}$'
            AND provider_request_digest ~ '^[a-f0-9]{64}$'
            AND requested_scope_digest ~ '^[a-f0-9]{64}$'
            AND (provider_receipt_digest IS NULL
                OR provider_receipt_digest ~ '^[a-f0-9]{64}$')
            AND (provider_evidence_digest IS NULL
                OR provider_evidence_digest ~ '^[a-f0-9]{64}$')
            AND (reconciliation_receipt_digest IS NULL
                OR reconciliation_receipt_digest ~ '^[a-f0-9]{64}$')
            AND (reconciliation_digest IS NULL
                OR reconciliation_digest ~ '^[a-f0-9]{64}$')
            AND (compensation_policy_digest IS NULL
                OR compensation_policy_digest ~ '^[a-f0-9]{64}$')
            AND (compensation_receipt_digest IS NULL
                OR compensation_receipt_digest ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_sys_connector_execution_provider_keys
        CHECK (provider_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND provider_operation ~ '^[A-Z][A-Z0-9_]{2,63}$'
            AND provider_idempotency_key
                ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{15,159}$'),
    CONSTRAINT ck_sys_connector_execution_counts
        CHECK (source_count BETWEEN 0 AND 10000000
            AND selected_item_count BETWEEN 0 AND source_count
            AND accepted_count BETWEEN 0 AND source_count
            AND rejected_count BETWEEN 0 AND source_count
            AND quarantined_count BETWEEN 0 AND rejected_count
            AND accepted_count + rejected_count <= source_count),
    CONSTRAINT ck_sys_connector_execution_terminal_counts
        CHECK (lifecycle_state NOT IN ('RECONCILED', 'PARTIAL')
            OR (accepted_count + rejected_count = source_count
                AND reconciliation_digest IS NOT NULL)),
    CONSTRAINT ck_sys_connector_execution_unknown_effect
        CHECK ((lifecycle_state <> 'RESULT_UNKNOWN'
                OR unresolved_external_effect)
            AND (lifecycle_state NOT IN (
                    'RECONCILED', 'DEAD_LETTER', 'CANCELLED')
                OR NOT unresolved_external_effect)),
    CONSTRAINT ck_sys_connector_execution_provider_receipt
        CHECK ((provider_receipt_ref IS NULL
                AND provider_receipt_digest IS NULL)
            OR (provider_receipt_ref IS NOT NULL
                AND provider_receipt_ref ~ '^ref_[A-Za-z0-9_-]{16,240}$'
                AND provider_receipt_digest IS NOT NULL)),
    CONSTRAINT ck_sys_connector_execution_reconciliation_receipt
        CHECK ((reconciliation_receipt_ref IS NULL
                AND reconciliation_receipt_digest IS NULL)
            OR (reconciliation_receipt_ref IS NOT NULL
                AND reconciliation_receipt_ref ~ '^ref_[A-Za-z0-9_-]{16,240}$'
                AND reconciliation_receipt_digest IS NOT NULL)),
    CONSTRAINT ck_sys_connector_execution_compensation
        CHECK ((compensation_policy_ref IS NULL
                AND compensation_policy_digest IS NULL
                AND compensation_receipt_ref IS NULL
                AND compensation_receipt_digest IS NULL)
            OR (compensation_policy_ref IS NOT NULL
                AND compensation_policy_ref
                    ~ '^policy_[A-Za-z0-9_-]{16,200}$'
                AND compensation_policy_digest IS NOT NULL
                AND compensation_receipt_ref IS NOT NULL
                AND compensation_receipt_ref
                    ~ '^ref_[A-Za-z0-9_-]{16,240}$'
                AND compensation_receipt_digest IS NOT NULL)),
    CONSTRAINT ck_sys_connector_execution_operations_exception
        CHECK (operations_exception_ref IS NULL
            OR (length(operations_exception_ref) BETWEEN 1 AND 512
                AND operations_exception_ref
                    ~ '^[A-Za-z0-9][A-Za-z0-9:/._-]*$'
                AND operations_exception_ref !~ '://|[?#]')),
    CONSTRAINT ck_sys_connector_execution_completion
        CHECK ((lifecycle_state IN ('RECONCILED', 'DEAD_LETTER', 'CANCELLED'))
                = (completed_at IS NOT NULL)
            AND (completed_at IS NULL OR completed_at >= requested_at))
);

CREATE INDEX ix_sys_connector_execution_state
    ON sys_connector_executions
        (tenant_id, lifecycle_state, updated_at, execution_id);

CREATE TABLE sys_connector_execution_items (
    tenant_id BIGINT NOT NULL,
    execution_item_id UUID NOT NULL DEFAULT gen_random_uuid(),
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    execution_id UUID NOT NULL,
    item_ordinal INTEGER NOT NULL,
    source_key_digest CHAR(64) NOT NULL,
    source_digest CHAR(64) NOT NULL,
    transformed_digest CHAR(64),
    mapping_version_id UUID NOT NULL,
    mapping_version_number BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    provider_item_key VARCHAR(160),
    provider_receipt_ref VARCHAR(244),
    provider_receipt_digest CHAR(64),
    provider_evidence_digest CHAR(64),
    reconciliation_digest CHAR(64),
    failure_class VARCHAR(80),
    retryable_snapshot BOOLEAN,
    quarantined_snapshot BOOLEAN NOT NULL DEFAULT FALSE,
    latest_attempt_number INTEGER NOT NULL DEFAULT 0,
    latest_attempt_fence BIGINT NOT NULL DEFAULT 0,
    object_revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, execution_item_id),
    CONSTRAINT uq_sys_connector_execution_item_public
        UNIQUE (tenant_id, public_id),
    CONSTRAINT uq_sys_connector_execution_item_ordinal
        UNIQUE (tenant_id, execution_id, item_ordinal),
    CONSTRAINT uq_sys_connector_execution_item_composite
        UNIQUE (tenant_id, execution_id, execution_item_id),
    CONSTRAINT fk_sys_connector_execution_item_execution
        FOREIGN KEY (tenant_id, execution_id)
        REFERENCES sys_connector_executions (tenant_id, execution_id),
    CONSTRAINT ck_sys_connector_execution_item_numbers
        CHECK (tenant_id > 0
            AND item_ordinal BETWEEN 1 AND 10000000
            AND mapping_version_number BETWEEN 1 AND 9007199254740991
            AND latest_attempt_number BETWEEN 0 AND 10000
            AND latest_attempt_fence BETWEEN 0 AND 9007199254740991
            AND object_revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_sys_connector_execution_item_state
        CHECK (lifecycle_state IN (
            'PENDING', 'TRANSFORMED', 'DISPATCHED', 'RESULT_UNKNOWN',
            'ACCEPTED', 'REJECTED_RETRYABLE', 'REJECTED_FINAL',
            'RECONCILED', 'CANCELLED')),
    CONSTRAINT ck_sys_connector_execution_item_digests
        CHECK (source_key_digest ~ '^[a-f0-9]{64}$'
            AND source_digest ~ '^[a-f0-9]{64}$'
            AND (transformed_digest IS NULL
                OR transformed_digest ~ '^[a-f0-9]{64}$')
            AND (provider_receipt_digest IS NULL
                OR provider_receipt_digest ~ '^[a-f0-9]{64}$')
            AND (provider_evidence_digest IS NULL
                OR provider_evidence_digest ~ '^[a-f0-9]{64}$')
            AND (reconciliation_digest IS NULL
                OR reconciliation_digest ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_sys_connector_execution_item_provider
        CHECK ((provider_item_key IS NULL
                AND provider_receipt_ref IS NULL
                AND provider_receipt_digest IS NULL)
            OR (provider_item_key IS NOT NULL
                AND provider_item_key
                    ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$'
                AND ((provider_receipt_ref IS NULL
                        AND provider_receipt_digest IS NULL)
                    OR (provider_receipt_ref IS NOT NULL
                        AND provider_receipt_ref
                            ~ '^ref_[A-Za-z0-9_-]{16,240}$'
                        AND provider_receipt_digest IS NOT NULL)))),
    CONSTRAINT ck_sys_connector_execution_item_failure
        CHECK ((failure_class IS NULL AND retryable_snapshot IS NULL)
            OR (failure_class IS NOT NULL
                AND failure_class ~ '^[A-Z][A-Z0-9_]{2,79}$'
                AND retryable_snapshot IS NOT NULL)),
    CONSTRAINT ck_sys_connector_execution_item_retryable_evidence
        CHECK (lifecycle_state <> 'REJECTED_RETRYABLE'
            OR (failure_class IS NOT NULL AND retryable_snapshot IS TRUE)),
    CONSTRAINT ck_sys_connector_execution_item_quarantine
        CHECK (NOT quarantined_snapshot
            OR lifecycle_state IN ('REJECTED_RETRYABLE', 'REJECTED_FINAL')),
    CONSTRAINT ck_sys_connector_execution_item_final_evidence
        CHECK (lifecycle_state <> 'REJECTED_FINAL'
            OR failure_class IS NOT NULL),
    CONSTRAINT ck_sys_connector_execution_item_state_evidence
        CHECK ((lifecycle_state = 'PENDING'
                AND transformed_digest IS NULL)
            OR (lifecycle_state = 'TRANSFORMED'
                AND transformed_digest IS NOT NULL)
            OR (lifecycle_state IN ('DISPATCHED', 'RESULT_UNKNOWN', 'ACCEPTED',
                    'RECONCILED')
                AND transformed_digest IS NOT NULL
                AND provider_item_key IS NOT NULL)
            OR lifecycle_state IN (
                'REJECTED_RETRYABLE', 'REJECTED_FINAL', 'CANCELLED'))
);

CREATE INDEX ix_sys_connector_execution_item_state
    ON sys_connector_execution_items
        (tenant_id, execution_id, lifecycle_state, item_ordinal);

CREATE TABLE sys_connector_execution_attempts (
    tenant_id BIGINT NOT NULL,
    execution_attempt_id UUID NOT NULL DEFAULT gen_random_uuid(),
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    execution_id UUID NOT NULL,
    execution_item_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    attempt_fence BIGINT NOT NULL,
    execution_revision_snapshot BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'READY',
    provider_idempotency_key VARCHAR(160) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    provider_receipt_ref VARCHAR(244),
    provider_receipt_digest CHAR(64),
    response_digest CHAR(64),
    reconciliation_digest CHAR(64),
    failure_class VARCHAR(80),
    dispatched_at TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    object_revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, execution_attempt_id),
    CONSTRAINT uq_sys_connector_execution_attempt_public
        UNIQUE (tenant_id, public_id),
    CONSTRAINT uq_sys_connector_execution_attempt_number
        UNIQUE (
            tenant_id, execution_id, execution_item_id, attempt_number),
    CONSTRAINT uq_sys_connector_execution_attempt_fence
        UNIQUE (tenant_id, execution_id, attempt_fence),
    CONSTRAINT uq_sys_connector_execution_attempt_provider_key
        UNIQUE (tenant_id, provider_idempotency_key),
    CONSTRAINT fk_sys_connector_execution_attempt_execution
        FOREIGN KEY (tenant_id, execution_id)
        REFERENCES sys_connector_executions (tenant_id, execution_id),
    CONSTRAINT fk_sys_connector_execution_attempt_item
        FOREIGN KEY (tenant_id, execution_id, execution_item_id)
        REFERENCES sys_connector_execution_items
            (tenant_id, execution_id, execution_item_id),
    CONSTRAINT ck_sys_connector_execution_attempt_numbers
        CHECK (tenant_id > 0
            AND attempt_number BETWEEN 1 AND 10000
            AND attempt_fence BETWEEN 1 AND 9007199254740991
            AND execution_revision_snapshot BETWEEN 1 AND 9007199254740991
            AND object_revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_sys_connector_execution_attempt_state
        CHECK (lifecycle_state IN (
            'READY', 'DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED',
            'RECONCILED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_sys_connector_execution_attempt_key
        CHECK (provider_idempotency_key
            ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{15,159}$'),
    CONSTRAINT ck_sys_connector_execution_attempt_digests
        CHECK (request_digest ~ '^[a-f0-9]{64}$'
            AND (provider_receipt_digest IS NULL
                OR provider_receipt_digest ~ '^[a-f0-9]{64}$')
            AND (response_digest IS NULL
                OR response_digest ~ '^[a-f0-9]{64}$')
            AND (reconciliation_digest IS NULL
                OR reconciliation_digest ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_sys_connector_execution_attempt_receipt
        CHECK ((provider_receipt_ref IS NULL
                AND provider_receipt_digest IS NULL)
            OR (provider_receipt_ref IS NOT NULL
                AND provider_receipt_ref ~ '^ref_[A-Za-z0-9_-]{16,240}$'
                AND provider_receipt_digest IS NOT NULL)),
    CONSTRAINT ck_sys_connector_execution_attempt_failure
        CHECK (failure_class IS NULL
            OR failure_class ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_sys_connector_execution_attempt_timestamps
        CHECK ((dispatched_at IS NULL OR dispatched_at >= created_at)
            AND (acknowledged_at IS NULL
                OR (dispatched_at IS NOT NULL
                    AND acknowledged_at >= dispatched_at))
            AND (completed_at IS NULL OR completed_at >= created_at)),
    CONSTRAINT ck_sys_connector_execution_attempt_state_evidence
        CHECK ((lifecycle_state = 'READY'
                AND dispatched_at IS NULL AND completed_at IS NULL)
            OR (lifecycle_state = 'DISPATCHED'
                AND dispatched_at IS NOT NULL AND completed_at IS NULL)
            OR (lifecycle_state = 'RESULT_UNKNOWN'
                AND dispatched_at IS NOT NULL AND completed_at IS NULL)
            OR (lifecycle_state = 'ACKNOWLEDGED'
                AND dispatched_at IS NOT NULL
                AND acknowledged_at IS NOT NULL
                AND completed_at IS NULL)
            OR (lifecycle_state IN ('RECONCILED', 'FAILED', 'CANCELLED')
                AND completed_at IS NOT NULL))
);

CREATE INDEX ix_sys_connector_execution_attempt_item
    ON sys_connector_execution_attempts
        (tenant_id, execution_id, execution_item_id, attempt_number);

-- CAS and append-only lifecycle guards. Subject columns of definition and
-- mapping versions can never be changed; a new immutable version is required.
CREATE OR REPLACE FUNCTION sys_connector_guard_definition_aggregate()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    selected_version_state VARCHAR(24);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SYS008_DEFINITION_DELETE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'INACTIVE'
           OR NEW.active_version_number IS NOT NULL
           OR NEW.aggregate_revision <> 1 THEN
            RAISE EXCEPTION 'SYS008_DEFINITION_INITIAL_STATE_REJECTED'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.lifecycle_state = 'RETIRED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'SYS008_RETIRED_DEFINITION_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.tenant_id, NEW.definition_id, NEW.public_id, NEW.connector_key,
           NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.definition_id, OLD.public_id, OLD.connector_key,
           OLD.created_at) THEN
        RAISE EXCEPTION 'SYS008_DEFINITION_IDENTITY_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF NEW IS DISTINCT FROM OLD
       AND NEW.aggregate_revision <> OLD.aggregate_revision + 1 THEN
        RAISE EXCEPTION 'SYS008_DEFINITION_CAS_REJECTED'
            USING ERRCODE = '40001';
    END IF;
    IF NEW.lifecycle_state <> OLD.lifecycle_state AND NOT (
        (OLD.lifecycle_state = 'INACTIVE' AND NEW.lifecycle_state = 'ACTIVE') OR
        (OLD.lifecycle_state = 'ACTIVE'
            AND NEW.lifecycle_state IN ('SUSPENDED', 'RETIRED')) OR
        (OLD.lifecycle_state = 'SUSPENDED'
            AND NEW.lifecycle_state IN ('ACTIVE', 'RETIRED'))
    ) THEN
        RAISE EXCEPTION 'SYS008_DEFINITION_TRANSITION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.lifecycle_state = 'ACTIVE' THEN
        SELECT lifecycle_state
          INTO selected_version_state
          FROM sys_connector_definition_versions
         WHERE tenant_id = NEW.tenant_id
           AND definition_id = NEW.definition_id
           AND version_number = NEW.active_version_number
         FOR SHARE;
        IF selected_version_state IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION 'SYS008_ACTIVE_DEFINITION_VERSION_REQUIRED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_connector_definition_guard
BEFORE INSERT OR UPDATE OR DELETE ON sys_connector_definitions
FOR EACH ROW EXECUTE FUNCTION sys_connector_guard_definition_aggregate();

CREATE OR REPLACE FUNCTION sys_connector_guard_definition_version()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SYS008_DEFINITION_VERSION_DELETE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'DRAFT' OR NEW.object_revision <> 1
           OR NEW.validation_receipt_id IS NOT NULL
           OR NEW.validation_evidence_digest IS NOT NULL
           OR NEW.validated_by_actor_id IS NOT NULL
           OR NEW.validated_at IS NOT NULL
           OR NEW.connection_test_receipt_id IS NOT NULL
           OR NEW.connection_test_result_ref IS NOT NULL
           OR NEW.connection_test_result_digest IS NOT NULL
           OR NEW.connection_test_evidence_digest IS NOT NULL
           OR NEW.tested_by_actor_id IS NOT NULL
           OR NEW.tested_at IS NOT NULL
           OR NEW.submission_receipt_id IS NOT NULL
           OR NEW.submission_evidence_digest IS NOT NULL
           OR NEW.submitted_by_actor_id IS NOT NULL
           OR NEW.submitted_at IS NOT NULL
           OR NEW.approval_receipt_id IS NOT NULL
           OR NEW.approval_evidence_digest IS NOT NULL
           OR NEW.approval_step_up_evidence_digest IS NOT NULL
           OR NEW.approved_by_actor_id IS NOT NULL
           OR NEW.approved_at IS NOT NULL
           OR NEW.activation_receipt_id IS NOT NULL
           OR NEW.activation_evidence_digest IS NOT NULL
           OR NEW.activation_step_up_evidence_digest IS NOT NULL
           OR NEW.activated_by_actor_id IS NOT NULL
           OR NEW.activated_at IS NOT NULL THEN
            RAISE EXCEPTION 'SYS008_DEFINITION_VERSION_INITIAL_STATE_REJECTED'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.tenant_id, NEW.definition_version_id, NEW.definition_id,
           NEW.version_number, NEW.connector_type, NEW.endpoint_uri,
           NEW.endpoint_policy_key, NEW.endpoint_policy_revision,
           NEW.secret_ref, NEW.secret_revision, NEW.definition_digest,
           NEW.subject_revision, NEW.author_actor_id, NEW.author_scope_digest,
           NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.definition_version_id, OLD.definition_id,
           OLD.version_number, OLD.connector_type, OLD.endpoint_uri,
           OLD.endpoint_policy_key, OLD.endpoint_policy_revision,
           OLD.secret_ref, OLD.secret_revision, OLD.definition_digest,
           OLD.subject_revision, OLD.author_actor_id, OLD.author_scope_digest,
           OLD.created_at) THEN
        RAISE EXCEPTION 'SYS008_DEFINITION_VERSION_SUBJECT_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'RETIRED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'SYS008_RETIRED_DEFINITION_VERSION_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF (OLD.validation_receipt_id IS NOT NULL AND
            ROW(NEW.validation_receipt_id,
                NEW.validation_evidence_digest, NEW.validated_by_actor_id,
                NEW.validated_at) IS DISTINCT FROM
            ROW(OLD.validation_receipt_id,
                OLD.validation_evidence_digest, OLD.validated_by_actor_id,
                OLD.validated_at))
       OR (OLD.connection_test_receipt_id IS NOT NULL AND
            ROW(NEW.connection_test_receipt_id,
                NEW.connection_test_result_ref,
                NEW.connection_test_result_digest,
                NEW.connection_test_evidence_digest, NEW.tested_by_actor_id,
                NEW.tested_at) IS DISTINCT FROM
            ROW(OLD.connection_test_receipt_id,
                OLD.connection_test_result_ref,
                OLD.connection_test_result_digest,
                OLD.connection_test_evidence_digest, OLD.tested_by_actor_id,
                OLD.tested_at))
       OR (OLD.submission_receipt_id IS NOT NULL AND
            ROW(NEW.submission_receipt_id,
                NEW.submission_evidence_digest, NEW.submitted_by_actor_id,
                NEW.submitted_at) IS DISTINCT FROM
            ROW(OLD.submission_receipt_id,
                OLD.submission_evidence_digest, OLD.submitted_by_actor_id,
                OLD.submitted_at))
       OR (OLD.approval_receipt_id IS NOT NULL AND
            ROW(NEW.approval_receipt_id, NEW.approval_evidence_digest,
                NEW.approval_step_up_evidence_digest,
                NEW.approved_by_actor_id, NEW.approved_at) IS DISTINCT FROM
            ROW(OLD.approval_receipt_id, OLD.approval_evidence_digest,
                OLD.approval_step_up_evidence_digest,
                OLD.approved_by_actor_id, OLD.approved_at))
       OR (OLD.activation_receipt_id IS NOT NULL AND
            ROW(NEW.activation_receipt_id, NEW.activation_evidence_digest,
                NEW.activation_step_up_evidence_digest,
                NEW.activated_by_actor_id, NEW.activated_at) IS DISTINCT FROM
            ROW(OLD.activation_receipt_id, OLD.activation_evidence_digest,
                OLD.activation_step_up_evidence_digest,
                OLD.activated_by_actor_id, OLD.activated_at)) THEN
        RAISE EXCEPTION 'SYS008_DEFINITION_LIFECYCLE_EVIDENCE_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF NEW IS DISTINCT FROM OLD
       AND NEW.object_revision <> OLD.object_revision + 1 THEN
        RAISE EXCEPTION 'SYS008_DEFINITION_VERSION_CAS_REJECTED'
            USING ERRCODE = '40001';
    END IF;
    IF NEW.lifecycle_state <> OLD.lifecycle_state AND NOT (
        (OLD.lifecycle_state = 'DRAFT'
            AND NEW.lifecycle_state = 'VALIDATED') OR
        (OLD.lifecycle_state = 'VALIDATED'
            AND NEW.lifecycle_state = 'TESTED') OR
        (OLD.lifecycle_state = 'TESTED'
            AND NEW.lifecycle_state = 'PENDING_APPROVAL') OR
        (OLD.lifecycle_state = 'PENDING_APPROVAL'
            AND NEW.lifecycle_state = 'APPROVED') OR
        (OLD.lifecycle_state = 'APPROVED'
            AND NEW.lifecycle_state = 'ACTIVE') OR
        (OLD.lifecycle_state = 'ACTIVE'
            AND NEW.lifecycle_state IN ('SUSPENDED', 'RETIRED')) OR
        (OLD.lifecycle_state = 'SUSPENDED'
            AND NEW.lifecycle_state IN ('ACTIVE', 'RETIRED'))
    ) THEN
        RAISE EXCEPTION 'SYS008_DEFINITION_VERSION_TRANSITION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.lifecycle_state IN ('APPROVED', 'ACTIVE', 'SUSPENDED', 'RETIRED')
       AND (NEW.approved_by_actor_id = NEW.author_actor_id
            OR NEW.approval_receipt_id IS NULL) THEN
        RAISE EXCEPTION 'SYS008_DEFINITION_MAKER_CHECKER_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED')
       AND (NEW.activated_by_actor_id = NEW.author_actor_id
            OR NEW.activation_receipt_id IS NULL
            OR NEW.connection_test_receipt_id IS NULL) THEN
        RAISE EXCEPTION 'SYS008_DEFINITION_ACTIVATION_EVIDENCE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_connector_definition_version_guard
BEFORE INSERT OR UPDATE OR DELETE ON sys_connector_definition_versions
FOR EACH ROW EXECUTE FUNCTION sys_connector_guard_definition_version();

CREATE OR REPLACE FUNCTION sys_connector_verify_active_definition_pointer()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    aggregate_state VARCHAR(16);
    active_version BIGINT;
BEGIN
    SELECT lifecycle_state, active_version_number
      INTO aggregate_state, active_version
      FROM sys_connector_definitions
     WHERE tenant_id = NEW.tenant_id
       AND definition_id = NEW.definition_id;
    IF aggregate_state = 'ACTIVE'
       AND active_version = NEW.version_number
       AND NEW.lifecycle_state <> 'ACTIVE' THEN
        RAISE EXCEPTION 'SYS008_ACTIVE_DEFINITION_POINTER_STALE'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_sys_connector_active_definition_pointer
AFTER INSERT OR UPDATE ON sys_connector_definition_versions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION sys_connector_verify_active_definition_pointer();

CREATE OR REPLACE FUNCTION sys_connector_guard_mapping_version()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SYS008_MAPPING_VERSION_DELETE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'DRAFT' OR NEW.object_revision <> 1
           OR NEW.validation_receipt_id IS NOT NULL
           OR NEW.validation_evidence_digest IS NOT NULL
           OR NEW.validated_by_actor_id IS NOT NULL
           OR NEW.validated_at IS NOT NULL
           OR NEW.dry_run_receipt_id IS NOT NULL
           OR NEW.dry_run_result_ref IS NOT NULL
           OR NEW.dry_run_result_digest IS NOT NULL
           OR NEW.dry_run_evidence_digest IS NOT NULL
           OR NEW.dry_run_source_count IS NOT NULL
           OR NEW.dry_run_rejected_count IS NOT NULL
           OR NEW.dry_run_by_actor_id IS NOT NULL
           OR NEW.dry_run_at IS NOT NULL
           OR NEW.submission_receipt_id IS NOT NULL
           OR NEW.submission_evidence_digest IS NOT NULL
           OR NEW.submitted_by_actor_id IS NOT NULL
           OR NEW.submitted_at IS NOT NULL
           OR NEW.approval_receipt_id IS NOT NULL
           OR NEW.approval_evidence_digest IS NOT NULL
           OR NEW.approval_step_up_evidence_digest IS NOT NULL
           OR NEW.approved_by_actor_id IS NOT NULL
           OR NEW.approved_at IS NOT NULL
           OR NEW.activation_receipt_id IS NOT NULL
           OR NEW.activation_evidence_digest IS NOT NULL
           OR NEW.activation_step_up_evidence_digest IS NOT NULL
           OR NEW.activated_by_actor_id IS NOT NULL
           OR NEW.activated_at IS NOT NULL THEN
            RAISE EXCEPTION 'SYS008_MAPPING_VERSION_INITIAL_STATE_REJECTED'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.tenant_id, NEW.mapping_version_id, NEW.definition_id,
           NEW.mapping_key, NEW.version_number, NEW.direction,
           NEW.source_schema_version, NEW.source_schema_digest,
           NEW.target_schema_version, NEW.target_schema_digest,
           NEW.mapping_ref, NEW.mapping_digest, NEW.compatibility_mode,
           NEW.effective_from, NEW.effective_to, NEW.subject_revision,
           NEW.author_actor_id, NEW.author_scope_digest, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.mapping_version_id, OLD.definition_id,
           OLD.mapping_key, OLD.version_number, OLD.direction,
           OLD.source_schema_version, OLD.source_schema_digest,
           OLD.target_schema_version, OLD.target_schema_digest,
           OLD.mapping_ref, OLD.mapping_digest, OLD.compatibility_mode,
           OLD.effective_from, OLD.effective_to, OLD.subject_revision,
           OLD.author_actor_id, OLD.author_scope_digest, OLD.created_at) THEN
        RAISE EXCEPTION 'SYS008_MAPPING_VERSION_SUBJECT_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'RETIRED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'SYS008_RETIRED_MAPPING_VERSION_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF (OLD.validation_receipt_id IS NOT NULL AND
            ROW(NEW.validation_receipt_id,
                NEW.validation_evidence_digest, NEW.validated_by_actor_id,
                NEW.validated_at) IS DISTINCT FROM
            ROW(OLD.validation_receipt_id,
                OLD.validation_evidence_digest, OLD.validated_by_actor_id,
                OLD.validated_at))
       OR (OLD.dry_run_receipt_id IS NOT NULL AND
            ROW(NEW.dry_run_receipt_id, NEW.dry_run_result_ref,
                NEW.dry_run_result_digest, NEW.dry_run_evidence_digest,
                NEW.dry_run_source_count, NEW.dry_run_rejected_count,
                NEW.dry_run_by_actor_id, NEW.dry_run_at) IS DISTINCT FROM
            ROW(OLD.dry_run_receipt_id, OLD.dry_run_result_ref,
                OLD.dry_run_result_digest, OLD.dry_run_evidence_digest,
                OLD.dry_run_source_count, OLD.dry_run_rejected_count,
                OLD.dry_run_by_actor_id, OLD.dry_run_at))
       OR (OLD.submission_receipt_id IS NOT NULL AND
            ROW(NEW.submission_receipt_id,
                NEW.submission_evidence_digest, NEW.submitted_by_actor_id,
                NEW.submitted_at) IS DISTINCT FROM
            ROW(OLD.submission_receipt_id,
                OLD.submission_evidence_digest, OLD.submitted_by_actor_id,
                OLD.submitted_at))
       OR (OLD.approval_receipt_id IS NOT NULL AND
            ROW(NEW.approval_receipt_id, NEW.approval_evidence_digest,
                NEW.approval_step_up_evidence_digest,
                NEW.approved_by_actor_id, NEW.approved_at) IS DISTINCT FROM
            ROW(OLD.approval_receipt_id, OLD.approval_evidence_digest,
                OLD.approval_step_up_evidence_digest,
                OLD.approved_by_actor_id, OLD.approved_at))
       OR (OLD.activation_receipt_id IS NOT NULL AND
            ROW(NEW.activation_receipt_id, NEW.activation_evidence_digest,
                NEW.activation_step_up_evidence_digest,
                NEW.activated_by_actor_id, NEW.activated_at) IS DISTINCT FROM
            ROW(OLD.activation_receipt_id, OLD.activation_evidence_digest,
                OLD.activation_step_up_evidence_digest,
                OLD.activated_by_actor_id, OLD.activated_at)) THEN
        RAISE EXCEPTION 'SYS008_MAPPING_LIFECYCLE_EVIDENCE_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF NEW IS DISTINCT FROM OLD
       AND NEW.object_revision <> OLD.object_revision + 1 THEN
        RAISE EXCEPTION 'SYS008_MAPPING_VERSION_CAS_REJECTED'
            USING ERRCODE = '40001';
    END IF;
    IF NEW.lifecycle_state <> OLD.lifecycle_state AND NOT (
        (OLD.lifecycle_state = 'DRAFT'
            AND NEW.lifecycle_state = 'VALIDATED') OR
        (OLD.lifecycle_state = 'VALIDATED'
            AND NEW.lifecycle_state = 'PENDING_APPROVAL') OR
        (OLD.lifecycle_state = 'PENDING_APPROVAL'
            AND NEW.lifecycle_state = 'APPROVED') OR
        (OLD.lifecycle_state = 'APPROVED'
            AND NEW.lifecycle_state = 'ACTIVE') OR
        (OLD.lifecycle_state = 'ACTIVE'
            AND NEW.lifecycle_state IN ('SUSPENDED', 'RETIRED')) OR
        (OLD.lifecycle_state = 'SUSPENDED'
            AND NEW.lifecycle_state IN ('ACTIVE', 'RETIRED'))
    ) THEN
        RAISE EXCEPTION 'SYS008_MAPPING_VERSION_TRANSITION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.lifecycle_state IN ('APPROVED', 'ACTIVE', 'SUSPENDED', 'RETIRED')
       AND (NEW.approved_by_actor_id = NEW.author_actor_id
            OR NEW.approval_receipt_id IS NULL) THEN
        RAISE EXCEPTION 'SYS008_MAPPING_MAKER_CHECKER_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED')
       AND (NEW.activated_by_actor_id = NEW.author_actor_id
            OR NEW.activation_receipt_id IS NULL
            OR NEW.dry_run_receipt_id IS NULL) THEN
        RAISE EXCEPTION 'SYS008_MAPPING_ACTIVATION_EVIDENCE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_connector_mapping_version_guard
BEFORE INSERT OR UPDATE OR DELETE ON sys_connector_mapping_versions
FOR EACH ROW EXECUTE FUNCTION sys_connector_guard_mapping_version();

-- Execution transitions retain the acceptance snapshot and enforce the exact
-- RESULT_UNKNOWN, failed-only retry and compensation boundaries.
CREATE OR REPLACE FUNCTION sys_connector_guard_execution()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    selected_definition_state VARCHAR(24);
    selected_definition_aggregate_state VARCHAR(16);
    selected_active_version_number BIGINT;
    selected_endpoint_policy_revision BIGINT;
    selected_secret_revision BIGINT;
    selected_mapping_state VARCHAR(24);
    selected_mapping_author BIGINT;
    receipt_first BOOLEAN := FALSE;
    current_attempt_receipt_authorized BOOLEAN := FALSE;
    live_child_exists BOOLEAN;
    provider_attempt_dispatched BOOLEAN;
    frozen_item_count BIGINT;
    accepted_item_count BIGINT;
    rejected_item_count BIGINT;
    quarantined_item_count BIGINT;
    reconciliation_digest_mismatch_count BIGINT;
    reconciliation_attempt_mismatch_count BIGINT;
    retry_attempt_count BIGINT;
    retry_attempt_mismatch_count BIGINT;
    retryable_item_count BIGINT;
    minimum_retry_fence BIGINT;
    maximum_retry_fence BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_DELETE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'ACCEPTED' OR NEW.object_revision <> 1
           OR NEW.retry_generation <> 0
           OR NEW.current_attempt_fence NOT IN (0, NEW.source_count)
           OR NEW.accepted_count <> 0 OR NEW.rejected_count <> 0
           OR NEW.quarantined_count <> 0
           OR NEW.provider_receipt_ref IS NOT NULL
           OR NEW.provider_receipt_digest IS NOT NULL
           OR NEW.provider_evidence_digest IS NOT NULL
           OR NEW.reconciliation_receipt_ref IS NOT NULL
           OR NEW.reconciliation_receipt_digest IS NOT NULL
           OR NEW.reconciliation_digest IS NOT NULL
           OR NEW.compensation_policy_ref IS NOT NULL
           OR NEW.compensation_policy_digest IS NOT NULL
           OR NEW.compensation_receipt_ref IS NOT NULL
           OR NEW.compensation_receipt_digest IS NOT NULL
           OR NEW.operations_exception_ref IS NOT NULL
           OR NEW.completed_at IS NOT NULL
           OR NEW.provider_failure_conclusive
           OR NEW.unresolved_external_effect THEN
            RAISE EXCEPTION 'SYS008_EXECUTION_INITIAL_STATE_REJECTED'
                USING ERRCODE = '23514';
        END IF;
        SELECT version.lifecycle_state, definition.lifecycle_state,
               definition.active_version_number,
               version.endpoint_policy_revision, version.secret_revision
          INTO selected_definition_state,
               selected_definition_aggregate_state,
               selected_active_version_number,
               selected_endpoint_policy_revision, selected_secret_revision
          FROM sys_connector_definition_versions version
          JOIN sys_connector_definitions definition
            ON definition.tenant_id = version.tenant_id
           AND definition.definition_id = version.definition_id
         WHERE version.tenant_id = NEW.tenant_id
           AND version.definition_id = NEW.definition_id
           AND version.definition_version_id = NEW.definition_version_id
           AND version.version_number = NEW.definition_version_number
         FOR SHARE OF version, definition;
        SELECT lifecycle_state, author_actor_id
          INTO selected_mapping_state, selected_mapping_author
          FROM sys_connector_mapping_versions
         WHERE tenant_id = NEW.tenant_id
           AND definition_id = NEW.definition_id
           AND mapping_version_id = NEW.mapping_version_id
           AND version_number = NEW.mapping_version_number
         FOR SHARE;
        SELECT TRUE
          INTO receipt_first
          FROM sys_hris_command_receipts receipt
         WHERE receipt.tenant_id = NEW.tenant_id
           AND receipt.public_id = NEW.original_command_receipt_id
           AND receipt.originating_action =
               'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.executions'
           AND receipt.authorization_revision = NEW.authorization_revision
           AND receipt.status IN ('ACCEPTED', 'RUNNING')
         FOR SHARE;
        IF selected_definition_state IS DISTINCT FROM 'ACTIVE'
           OR selected_definition_aggregate_state IS DISTINCT FROM 'ACTIVE'
           OR selected_active_version_number IS DISTINCT FROM
                NEW.definition_version_number
           OR selected_endpoint_policy_revision IS DISTINCT FROM
                NEW.endpoint_policy_revision
           OR selected_secret_revision IS DISTINCT FROM NEW.secret_revision
           OR selected_mapping_state IS DISTINCT FROM 'ACTIVE'
           OR selected_mapping_author = NEW.requested_by_actor_id
           OR receipt_first IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'SYS008_APPROVED_RECEIPT_FIRST_EXECUTION_REQUIRED'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.tenant_id, NEW.execution_id, NEW.public_id, NEW.definition_id,
           NEW.definition_version_id, NEW.definition_version_number,
           NEW.mapping_version_id, NEW.mapping_version_number,
           NEW.endpoint_policy_revision, NEW.secret_revision, NEW.operation,
           NEW.payload_ref, NEW.payload_digest, NEW.source_count,
           NEW.source_item_set_digest, NEW.provider_key,
           NEW.provider_operation, NEW.provider_idempotency_key,
           NEW.provider_request_digest, NEW.authorization_revision,
           NEW.population_kind, NEW.legal_entity_public_id,
           NEW.original_command_receipt_id, NEW.requested_by_actor_id,
           NEW.requested_scope_digest, NEW.requested_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.execution_id, OLD.public_id, OLD.definition_id,
           OLD.definition_version_id, OLD.definition_version_number,
           OLD.mapping_version_id, OLD.mapping_version_number,
           OLD.endpoint_policy_revision, OLD.secret_revision, OLD.operation,
           OLD.payload_ref, OLD.payload_digest, OLD.source_count,
           OLD.source_item_set_digest, OLD.provider_key,
           OLD.provider_operation, OLD.provider_idempotency_key,
           OLD.provider_request_digest, OLD.authorization_revision,
           OLD.population_kind, OLD.legal_entity_public_id,
           OLD.original_command_receipt_id, OLD.requested_by_actor_id,
           OLD.requested_scope_digest, OLD.requested_at) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ACCEPTANCE_SNAPSHOT_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('RECONCILED', 'DEAD_LETTER', 'CANCELLED')
       AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'SYS008_TERMINAL_EXECUTION_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('ACKNOWLEDGED', 'RESULT_UNKNOWN')
       AND NEW.lifecycle_state = 'RECONCILING' THEN
        SELECT ((attempt.provider_receipt_ref IS NULL
                    AND attempt.provider_receipt_digest IS NULL)
                OR (attempt.provider_receipt_ref = NEW.provider_receipt_ref
                    AND attempt.provider_receipt_digest =
                        NEW.provider_receipt_digest))
          INTO current_attempt_receipt_authorized
          FROM sys_connector_execution_attempts attempt
         WHERE attempt.tenant_id = NEW.tenant_id
           AND attempt.execution_id = NEW.execution_id
         ORDER BY attempt.attempt_fence DESC
         LIMIT 1
         FOR SHARE;
    END IF;
    IF (ROW(NEW.provider_receipt_ref, NEW.provider_receipt_digest)
            IS DISTINCT FROM
            ROW(OLD.provider_receipt_ref, OLD.provider_receipt_digest)
        AND NOT ((OLD.lifecycle_state = 'DISPATCHED'
                AND NEW.lifecycle_state IN (
                    'ACKNOWLEDGED', 'FAILED', 'RESULT_UNKNOWN'))
            OR (OLD.lifecycle_state = 'RESULT_UNKNOWN'
                AND NEW.lifecycle_state = 'RECONCILING'
                AND COALESCE(current_attempt_receipt_authorized, FALSE)
                AND NEW.provider_receipt_ref IS NOT NULL
                AND NEW.provider_receipt_digest IS NOT NULL
                AND NEW.reconciliation_receipt_ref =
                    NEW.provider_receipt_ref
                AND NEW.reconciliation_receipt_digest =
                    NEW.provider_receipt_digest)))
       OR (NEW.provider_evidence_digest IS DISTINCT FROM
                OLD.provider_evidence_digest
        AND NOT (OLD.lifecycle_state = 'DISPATCHED'
            AND NEW.lifecycle_state IN (
                'ACKNOWLEDGED', 'FAILED', 'RESULT_UNKNOWN')))
       OR (ROW(NEW.reconciliation_receipt_ref,
                  NEW.reconciliation_receipt_digest) IS DISTINCT FROM
            ROW(OLD.reconciliation_receipt_ref,
                OLD.reconciliation_receipt_digest)
        AND NOT (OLD.lifecycle_state IN ('ACKNOWLEDGED', 'RESULT_UNKNOWN')
            AND NEW.lifecycle_state = 'RECONCILING'))
       OR (NEW.reconciliation_digest IS DISTINCT FROM
                OLD.reconciliation_digest
        AND NOT (OLD.lifecycle_state = 'RECONCILING'
            AND NEW.lifecycle_state IN (
                'RECONCILED', 'PARTIAL', 'FAILED', 'RESULT_UNKNOWN')))
       OR (OLD.compensation_policy_ref IS NOT NULL AND
            ROW(NEW.compensation_policy_ref, NEW.compensation_policy_digest,
                NEW.compensation_receipt_ref,
                NEW.compensation_receipt_digest) IS DISTINCT FROM
            ROW(OLD.compensation_policy_ref, OLD.compensation_policy_digest,
                OLD.compensation_receipt_ref,
                OLD.compensation_receipt_digest)) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_EVIDENCE_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF NEW IS DISTINCT FROM OLD
       AND NEW.object_revision <> OLD.object_revision + 1 THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_CAS_REJECTED'
            USING ERRCODE = '40001';
    END IF;
    IF NEW.current_attempt_fence < OLD.current_attempt_fence
       OR NEW.retry_generation < OLD.retry_generation THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_FENCE_REGRESSION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF NOT (OLD.lifecycle_state IN ('FAILED', 'PARTIAL')
            AND NEW.lifecycle_state = 'ACCEPTED')
       AND ROW(NEW.retry_generation,
               NEW.selected_item_count, NEW.selected_item_digest)
           IS DISTINCT FROM
           ROW(OLD.retry_generation,
               OLD.selected_item_count, OLD.selected_item_digest) THEN
        RAISE EXCEPTION 'SYS008_RETRY_SNAPSHOT_TRANSITION_REQUIRED'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.current_attempt_fence <> OLD.current_attempt_fence
       AND NOT (
           (OLD.lifecycle_state = 'TRANSFORMING'
                AND NEW.lifecycle_state = 'DISPATCHED'
                AND NEW.current_attempt_fence IN (
                    OLD.current_attempt_fence,
                    OLD.current_attempt_fence + 1))
           OR (OLD.lifecycle_state IN ('FAILED', 'PARTIAL')
                AND NEW.lifecycle_state = 'ACCEPTED')
           OR (NEW.lifecycle_state = OLD.lifecycle_state
                AND OLD.lifecycle_state IN ('DISPATCHED', 'RECONCILING')
                AND NEW.current_attempt_fence = OLD.current_attempt_fence + 1)
       ) THEN
        RAISE EXCEPTION 'SYS008_ATTEMPT_FENCE_ALLOCATION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.lifecycle_state <> OLD.lifecycle_state AND NOT (
        (OLD.lifecycle_state = 'ACCEPTED'
            AND NEW.lifecycle_state IN ('TRANSFORMING', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'TRANSFORMING'
            AND NEW.lifecycle_state IN ('DISPATCHED', 'FAILED', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'DISPATCHED'
            AND NEW.lifecycle_state IN (
                'ACKNOWLEDGED', 'RESULT_UNKNOWN', 'FAILED', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'ACKNOWLEDGED'
            AND NEW.lifecycle_state IN ('RECONCILING', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'RESULT_UNKNOWN'
            AND NEW.lifecycle_state IN ('RECONCILING', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'RECONCILING'
            AND NEW.lifecycle_state IN (
                'RECONCILED', 'PARTIAL', 'FAILED', 'RESULT_UNKNOWN',
                'CANCELLED')) OR
        (OLD.lifecycle_state = 'FAILED'
            AND NEW.lifecycle_state IN (
                'ACCEPTED', 'DEAD_LETTER', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'PARTIAL'
            AND NEW.lifecycle_state IN (
                'ACCEPTED', 'DEAD_LETTER', 'CANCELLED'))
    ) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_TRANSITION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'ACCEPTED'
       AND NEW.lifecycle_state = 'TRANSFORMING' THEN
        SELECT COUNT(*) INTO frozen_item_count
          FROM sys_connector_execution_items item
         WHERE item.tenant_id = NEW.tenant_id
           AND item.execution_id = NEW.execution_id;
        IF frozen_item_count <> NEW.source_count THEN
            RAISE EXCEPTION 'SYS008_SOURCE_ITEM_SET_CARDINALITY_REJECTED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.lifecycle_state IN ('DISPATCHED', 'RECONCILING')
       AND NEW.lifecycle_state = 'FAILED'
       AND (NOT NEW.provider_failure_conclusive
            OR NEW.unresolved_external_effect
            OR NEW.provider_evidence_digest IS NULL
            OR NEW.provider_receipt_ref IS NULL
            OR NEW.provider_receipt_digest IS NULL
            OR (OLD.lifecycle_state = 'RECONCILING'
                AND (NEW.reconciliation_receipt_ref IS NULL
                    OR NEW.reconciliation_receipt_digest IS NULL
                    OR NEW.reconciliation_digest IS NULL))) THEN
        RAISE EXCEPTION 'SYS008_POST_DISPATCH_FAILURE_EVIDENCE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'DISPATCHED'
       AND NEW.lifecycle_state = 'ACKNOWLEDGED'
       AND (NEW.provider_receipt_ref IS NULL
            OR NEW.provider_receipt_digest IS NULL
            OR NEW.provider_evidence_digest IS NULL
            OR NEW.unresolved_external_effect) THEN
        RAISE EXCEPTION 'SYS008_PROVIDER_ACKNOWLEDGEMENT_EVIDENCE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('ACKNOWLEDGED', 'RESULT_UNKNOWN')
       AND NEW.lifecycle_state = 'RECONCILING'
       AND (NOT COALESCE(current_attempt_receipt_authorized, FALSE)
            OR NEW.reconciliation_receipt_ref IS NULL
            OR NEW.reconciliation_receipt_digest IS NULL
            OR NEW.provider_receipt_ref IS NULL
            OR NEW.provider_receipt_digest IS NULL
            OR NEW.reconciliation_receipt_ref <>
                NEW.provider_receipt_ref
            OR NEW.reconciliation_receipt_digest <>
                NEW.provider_receipt_digest) THEN
        RAISE EXCEPTION 'SYS008_SAME_KEY_RECONCILIATION_REQUIRED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'RECONCILING'
       AND NEW.lifecycle_state = 'RESULT_UNKNOWN'
       AND NEW.operations_exception_ref IS NULL THEN
        RAISE EXCEPTION 'SYS008_RESULT_UNKNOWN_EXCEPTION_REQUIRED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'RECONCILING'
       AND NEW.lifecycle_state IN ('RECONCILED', 'PARTIAL')
       AND (NEW.reconciliation_receipt_ref IS NULL
            OR NEW.reconciliation_receipt_digest IS NULL
            OR NEW.reconciliation_digest IS NULL
            OR NEW.unresolved_external_effect) THEN
        RAISE EXCEPTION 'SYS008_RECONCILIATION_EVIDENCE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'RECONCILING'
       AND NEW.lifecycle_state IN ('RECONCILED', 'PARTIAL') THEN
        SELECT COUNT(*),
               COUNT(*) FILTER (WHERE item.lifecycle_state IN (
                   'ACCEPTED', 'RECONCILED')),
               COUNT(*) FILTER (WHERE item.lifecycle_state IN (
                   'REJECTED_RETRYABLE', 'REJECTED_FINAL')),
               COUNT(*) FILTER (WHERE item.quarantined_snapshot),
               COUNT(*) FILTER (WHERE item.reconciliation_digest
                    IS DISTINCT FROM NEW.reconciliation_digest)
          INTO frozen_item_count, accepted_item_count, rejected_item_count,
               quarantined_item_count, reconciliation_digest_mismatch_count
          FROM sys_connector_execution_items item
         WHERE item.tenant_id = NEW.tenant_id
           AND item.execution_id = NEW.execution_id;
        IF frozen_item_count <> NEW.source_count
           OR accepted_item_count <> NEW.accepted_count
           OR rejected_item_count <> NEW.rejected_count
           OR quarantined_item_count <> NEW.quarantined_count
           OR reconciliation_digest_mismatch_count <> 0
           OR accepted_item_count + rejected_item_count <> frozen_item_count THEN
            RAISE EXCEPTION 'SYS008_RECONCILIATION_CHILD_COUNTS_REJECTED'
                USING ERRCODE = '23514';
        END IF;
        SELECT COUNT(*)
          INTO reconciliation_attempt_mismatch_count
          FROM sys_connector_execution_items item
          LEFT JOIN sys_connector_execution_attempts attempt
            ON attempt.tenant_id = item.tenant_id
           AND attempt.execution_id = item.execution_id
           AND attempt.execution_item_id = item.execution_item_id
           AND attempt.attempt_number = item.latest_attempt_number
           AND attempt.attempt_fence = item.latest_attempt_fence
         WHERE item.tenant_id = NEW.tenant_id
           AND item.execution_id = NEW.execution_id
           AND (attempt.execution_attempt_id IS NULL
                OR item.provider_receipt_ref
                    IS DISTINCT FROM NEW.provider_receipt_ref
                OR item.provider_receipt_digest
                    IS DISTINCT FROM NEW.provider_receipt_digest
                OR attempt.provider_receipt_ref
                    IS DISTINCT FROM NEW.provider_receipt_ref
                OR attempt.provider_receipt_digest
                    IS DISTINCT FROM NEW.provider_receipt_digest
                OR attempt.reconciliation_digest
                    IS DISTINCT FROM NEW.reconciliation_digest);
        IF reconciliation_attempt_mismatch_count <> 0 THEN
            RAISE EXCEPTION 'SYS008_RECONCILIATION_ATTEMPT_DIGEST_REJECTED'
                USING ERRCODE = '23514';
        END IF;
        SELECT EXISTS (
            SELECT 1 FROM sys_connector_execution_attempts attempt
             WHERE attempt.tenant_id = NEW.tenant_id
               AND attempt.execution_id = NEW.execution_id
               AND attempt.lifecycle_state IN (
                   'READY', 'DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED'))
          INTO live_child_exists;
        IF live_child_exists THEN
            RAISE EXCEPTION 'SYS008_RECONCILIATION_LIVE_ATTEMPT_REJECTED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.lifecycle_state IN ('FAILED', 'PARTIAL')
       AND NEW.lifecycle_state = 'ACCEPTED' THEN
        SELECT COUNT(*), MIN(attempt.attempt_fence),
               MAX(attempt.attempt_fence),
               COUNT(*) FILTER (WHERE
                   attempt.lifecycle_state <> 'READY'
                   OR attempt.execution_revision_snapshot <> OLD.object_revision
                   OR item.execution_item_id IS NULL
                   OR item.lifecycle_state <> 'DISPATCHED'
                   OR item.retryable_snapshot IS DISTINCT FROM TRUE
                   OR item.latest_attempt_number <> attempt.attempt_number
                   OR item.latest_attempt_fence <> attempt.attempt_fence)
          INTO retry_attempt_count, minimum_retry_fence,
               maximum_retry_fence, retry_attempt_mismatch_count
          FROM sys_connector_execution_attempts attempt
          LEFT JOIN sys_connector_execution_items item
            ON item.tenant_id = attempt.tenant_id
           AND item.execution_id = attempt.execution_id
           AND item.execution_item_id = attempt.execution_item_id
         WHERE attempt.tenant_id = NEW.tenant_id
           AND attempt.execution_id = NEW.execution_id
           AND attempt.attempt_fence > OLD.current_attempt_fence
           AND attempt.attempt_fence <= NEW.current_attempt_fence;
        SELECT COUNT(*)
          INTO retryable_item_count
          FROM sys_connector_execution_items item
         WHERE item.tenant_id = NEW.tenant_id
           AND item.execution_id = NEW.execution_id
           AND item.lifecycle_state = 'REJECTED_RETRYABLE'
           AND item.retryable_snapshot = TRUE;
        SELECT EXISTS (
            SELECT 1 FROM sys_connector_execution_attempts attempt
             WHERE attempt.tenant_id = NEW.tenant_id
               AND attempt.execution_id = NEW.execution_id
               AND attempt.attempt_fence <= OLD.current_attempt_fence
               AND attempt.lifecycle_state IN (
                   'READY', 'DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED'))
          INTO live_child_exists;
        IF NEW.retry_generation <> OLD.retry_generation + 1
           OR NEW.selected_item_count < 1
           OR retry_attempt_count <> NEW.selected_item_count
           OR retry_attempt_mismatch_count <> 0
           OR retryable_item_count <> 0
           OR live_child_exists
           OR minimum_retry_fence <> OLD.current_attempt_fence + 1
           OR maximum_retry_fence <> NEW.current_attempt_fence
           OR NEW.current_attempt_fence - OLD.current_attempt_fence
                <> retry_attempt_count THEN
            RAISE EXCEPTION 'SYS008_FAILED_ONLY_RETRY_SET_REJECTED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.lifecycle_state IN ('FAILED', 'PARTIAL')
       AND NEW.lifecycle_state = 'DEAD_LETTER' THEN
        SELECT EXISTS (
            SELECT 1 FROM sys_connector_execution_attempts attempt
             WHERE attempt.tenant_id = NEW.tenant_id
               AND attempt.execution_id = NEW.execution_id
               AND attempt.lifecycle_state IN (
                   'READY', 'DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED'))
          INTO live_child_exists;
        IF live_child_exists THEN
            RAISE EXCEPTION 'SYS008_DEAD_LETTER_LIVE_ATTEMPT_REJECTED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.lifecycle_state = 'RECONCILING'
       AND NEW.lifecycle_state = 'RECONCILED' THEN
        SELECT EXISTS (
            SELECT 1 FROM sys_connector_execution_items item
             WHERE item.tenant_id = NEW.tenant_id
               AND item.execution_id = NEW.execution_id
               AND item.lifecycle_state NOT IN (
                   'RECONCILED', 'REJECTED_FINAL', 'CANCELLED')
            UNION ALL
            SELECT 1 FROM sys_connector_execution_attempts attempt
             WHERE attempt.tenant_id = NEW.tenant_id
               AND attempt.execution_id = NEW.execution_id
               AND attempt.lifecycle_state NOT IN (
                   'RECONCILED', 'FAILED', 'CANCELLED'))
          INTO live_child_exists;
        IF live_child_exists THEN
            RAISE EXCEPTION 'SYS008_RECONCILED_CHILD_SET_NOT_TERMINAL'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.lifecycle_state = 'CANCELLED' THEN
        SELECT EXISTS (
            SELECT 1 FROM sys_connector_execution_attempts attempt
             WHERE attempt.tenant_id = NEW.tenant_id
               AND attempt.execution_id = NEW.execution_id
               AND attempt.lifecycle_state IN (
                   'DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED',
                   'RECONCILED')
            UNION ALL
            SELECT 1 FROM sys_connector_execution_items item
             WHERE item.tenant_id = NEW.tenant_id
               AND item.execution_id = NEW.execution_id
               AND item.lifecycle_state IN (
                   'RESULT_UNKNOWN', 'ACCEPTED', 'RECONCILED'))
          INTO provider_attempt_dispatched;
        provider_attempt_dispatched := provider_attempt_dispatched
            OR (OLD.provider_receipt_ref IS NOT NULL
                AND NOT (OLD.lifecycle_state = 'FAILED'
                    AND OLD.provider_failure_conclusive
                    AND NOT OLD.unresolved_external_effect));
        IF provider_attempt_dispatched
           AND (NEW.compensation_policy_ref IS NULL
                OR NEW.compensation_policy_digest IS NULL
                OR NEW.compensation_receipt_ref IS NULL
                OR NEW.compensation_receipt_digest IS NULL
                OR NEW.unresolved_external_effect) THEN
            RAISE EXCEPTION 'SYS008_ACTUAL_PROVIDER_EFFECT_COMPENSATION_REQUIRED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.lifecycle_state IN (
            'DISPATCHED', 'ACKNOWLEDGED', 'RESULT_UNKNOWN', 'RECONCILING',
            'PARTIAL')
       AND NEW.lifecycle_state = 'CANCELLED'
       AND (NEW.compensation_policy_ref IS NULL
            OR NEW.compensation_policy_digest IS NULL
            OR NEW.compensation_receipt_ref IS NULL
            OR NEW.compensation_receipt_digest IS NULL
            OR NEW.unresolved_external_effect) THEN
        RAISE EXCEPTION 'SYS008_POST_DISPATCH_COMPENSATION_REQUIRED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'FAILED'
       AND NEW.lifecycle_state = 'CANCELLED'
       AND NEW.unresolved_external_effect
       AND (NEW.compensation_policy_ref IS NULL
            OR NEW.compensation_receipt_ref IS NULL) THEN
        RAISE EXCEPTION 'SYS008_FAILED_EXECUTION_COMPENSATION_REQUIRED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_connector_execution_guard
BEFORE INSERT OR UPDATE OR DELETE ON sys_connector_executions
FOR EACH ROW EXECUTE FUNCTION sys_connector_guard_execution();

CREATE OR REPLACE FUNCTION sys_connector_guard_execution_item()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent_compensated BOOLEAN;
    parent_state VARCHAR(24);
    parent_mapping_version_id UUID;
    parent_mapping_version_number BIGINT;
    parent_source_count INTEGER;
    parent_retry_generation INTEGER;
    retry_attempt_exists BOOLEAN;
    latest_attempt_receipt_matches BOOLEAN := FALSE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_DELETE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    SELECT lifecycle_state, mapping_version_id, mapping_version_number,
           source_count, retry_generation
      INTO parent_state, parent_mapping_version_id,
           parent_mapping_version_number, parent_source_count,
           parent_retry_generation
      FROM sys_connector_executions
     WHERE tenant_id = NEW.tenant_id
       AND execution_id = NEW.execution_id
     FOR UPDATE;
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'PENDING' OR NEW.object_revision <> 1
           OR NEW.latest_attempt_number <> 0
           OR NEW.latest_attempt_fence <> 0
           OR NEW.transformed_digest IS NOT NULL
           OR NEW.provider_item_key IS NOT NULL
           OR NEW.provider_receipt_ref IS NOT NULL
           OR NEW.provider_receipt_digest IS NOT NULL
           OR NEW.provider_evidence_digest IS NOT NULL
           OR NEW.reconciliation_digest IS NOT NULL
           OR NEW.failure_class IS NOT NULL
           OR NEW.retryable_snapshot IS NOT NULL
           OR NEW.quarantined_snapshot THEN
            RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_INITIAL_STATE_REJECTED'
                USING ERRCODE = '23514';
        END IF;
        IF parent_state IS DISTINCT FROM 'ACCEPTED'
           OR parent_retry_generation <> 0
           OR NEW.item_ordinal > parent_source_count
           OR NEW.mapping_version_id IS DISTINCT FROM parent_mapping_version_id
           OR NEW.mapping_version_number IS DISTINCT FROM
                parent_mapping_version_number THEN
            RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_PARENT_SNAPSHOT_REJECTED'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF parent_state IN ('RECONCILED', 'DEAD_LETTER', 'CANCELLED')
       AND NOT (
           (parent_state = 'DEAD_LETTER'
                AND OLD.lifecycle_state = 'REJECTED_RETRYABLE'
                AND NEW.lifecycle_state = 'REJECTED_FINAL')
           OR (parent_state = 'CANCELLED'
                AND OLD.lifecycle_state IN (
                    'PENDING', 'TRANSFORMED', 'DISPATCHED',
                    'RESULT_UNKNOWN', 'ACCEPTED', 'REJECTED_RETRYABLE')
                AND NEW.lifecycle_state = 'CANCELLED')) THEN
        RAISE EXCEPTION 'SYS008_TERMINAL_PARENT_ITEM_MUTATION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.tenant_id, NEW.execution_item_id, NEW.public_id,
           NEW.execution_id, NEW.item_ordinal, NEW.source_key_digest,
           NEW.source_digest, NEW.mapping_version_id,
           NEW.mapping_version_number, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.execution_item_id, OLD.public_id,
           OLD.execution_id, OLD.item_ordinal, OLD.source_key_digest,
           OLD.source_digest, OLD.mapping_version_id,
           OLD.mapping_version_number, OLD.created_at) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_SNAPSHOT_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('REJECTED_FINAL', 'RECONCILED', 'CANCELLED')
       AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'SYS008_TERMINAL_EXECUTION_ITEM_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'RESULT_UNKNOWN'
       AND NEW.lifecycle_state IN (
            'ACCEPTED', 'REJECTED_RETRYABLE', 'REJECTED_FINAL',
            'RECONCILED') THEN
        SELECT attempt.provider_receipt_ref = NEW.provider_receipt_ref
                   AND attempt.provider_receipt_digest =
                       NEW.provider_receipt_digest
          INTO latest_attempt_receipt_matches
          FROM sys_connector_execution_attempts attempt
         WHERE attempt.tenant_id = NEW.tenant_id
           AND attempt.execution_id = NEW.execution_id
           AND attempt.execution_item_id = NEW.execution_item_id
           AND attempt.attempt_number = NEW.latest_attempt_number
           AND attempt.attempt_fence = NEW.latest_attempt_fence
         FOR SHARE;
    END IF;
    IF (NEW.transformed_digest IS DISTINCT FROM OLD.transformed_digest
        AND NOT ((OLD.lifecycle_state = 'PENDING'
                AND NEW.lifecycle_state IN (
                    'TRANSFORMED', 'REJECTED_RETRYABLE', 'REJECTED_FINAL'))
            OR (OLD.lifecycle_state = 'REJECTED_RETRYABLE'
                AND NEW.lifecycle_state = 'DISPATCHED')))
       OR (NEW.provider_item_key IS DISTINCT FROM OLD.provider_item_key
        AND NOT (OLD.lifecycle_state IN ('TRANSFORMED', 'REJECTED_RETRYABLE')
            AND NEW.lifecycle_state = 'DISPATCHED'))
       OR (ROW(NEW.provider_receipt_ref, NEW.provider_receipt_digest)
            IS DISTINCT FROM
            ROW(OLD.provider_receipt_ref, OLD.provider_receipt_digest)
        AND NOT ((OLD.lifecycle_state = 'DISPATCHED'
                AND NEW.lifecycle_state IN (
                    'RESULT_UNKNOWN', 'ACCEPTED',
                    'REJECTED_RETRYABLE', 'REJECTED_FINAL'))
            OR (OLD.lifecycle_state = 'RESULT_UNKNOWN'
                AND NEW.lifecycle_state IN (
                    'ACCEPTED', 'REJECTED_RETRYABLE', 'REJECTED_FINAL',
                    'RECONCILED')
                AND COALESCE(latest_attempt_receipt_matches, FALSE)
                AND NEW.provider_receipt_ref IS NOT NULL
                AND NEW.provider_receipt_digest IS NOT NULL)))
       OR (NEW.provider_evidence_digest IS DISTINCT FROM
                OLD.provider_evidence_digest
        AND NOT (OLD.lifecycle_state = 'DISPATCHED'
            AND NEW.lifecycle_state IN (
                'RESULT_UNKNOWN', 'ACCEPTED',
                'REJECTED_RETRYABLE', 'REJECTED_FINAL')))
       OR (NEW.reconciliation_digest IS DISTINCT FROM
                OLD.reconciliation_digest
        AND NOT ((OLD.lifecycle_state = 'RESULT_UNKNOWN'
                AND NEW.lifecycle_state IN (
                    'ACCEPTED', 'REJECTED_RETRYABLE', 'REJECTED_FINAL',
                    'RECONCILED'))
            OR (OLD.lifecycle_state = 'ACCEPTED'
                AND NEW.lifecycle_state = 'RECONCILED')))
       OR (ROW(NEW.failure_class, NEW.retryable_snapshot) IS DISTINCT FROM
                ROW(OLD.failure_class, OLD.retryable_snapshot)
        AND NOT ((OLD.lifecycle_state = 'DISPATCHED'
                AND NEW.lifecycle_state IN (
                    'RESULT_UNKNOWN', 'ACCEPTED',
                    'REJECTED_RETRYABLE', 'REJECTED_FINAL'))
            OR (OLD.lifecycle_state = 'RESULT_UNKNOWN'
                AND NEW.lifecycle_state IN (
                    'ACCEPTED', 'REJECTED_RETRYABLE', 'REJECTED_FINAL',
                    'RECONCILED'))
            OR (OLD.lifecycle_state = 'PENDING'
                AND NEW.lifecycle_state IN (
                    'REJECTED_RETRYABLE', 'REJECTED_FINAL'))))
       OR (NEW.quarantined_snapshot IS DISTINCT FROM
                OLD.quarantined_snapshot
        AND NOT ((OLD.lifecycle_state IN ('DISPATCHED', 'RESULT_UNKNOWN')
                AND NEW.lifecycle_state IN (
                    'ACCEPTED', 'REJECTED_RETRYABLE', 'REJECTED_FINAL',
                    'RECONCILED'))
            OR (OLD.lifecycle_state = 'PENDING'
                AND NEW.lifecycle_state IN (
                    'REJECTED_RETRYABLE', 'REJECTED_FINAL'))
            OR (OLD.lifecycle_state = 'REJECTED_RETRYABLE'
                AND NEW.lifecycle_state = 'DISPATCHED'))) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_EVIDENCE_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF NEW IS DISTINCT FROM OLD
       AND NEW.object_revision <> OLD.object_revision + 1 THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_CAS_REJECTED'
            USING ERRCODE = '40001';
    END IF;
    IF NEW.latest_attempt_number < OLD.latest_attempt_number
       OR NEW.latest_attempt_fence < OLD.latest_attempt_fence THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_FENCE_REGRESSION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.lifecycle_state <> OLD.lifecycle_state AND NOT (
        (OLD.lifecycle_state = 'PENDING'
            AND NEW.lifecycle_state IN (
                'TRANSFORMED', 'REJECTED_RETRYABLE', 'REJECTED_FINAL',
                'CANCELLED')) OR
        (OLD.lifecycle_state = 'TRANSFORMED'
            AND NEW.lifecycle_state IN ('DISPATCHED', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'DISPATCHED'
            AND NEW.lifecycle_state IN (
                'RESULT_UNKNOWN', 'ACCEPTED', 'REJECTED_RETRYABLE',
                'REJECTED_FINAL', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'RESULT_UNKNOWN'
            AND NEW.lifecycle_state IN (
                'ACCEPTED', 'REJECTED_RETRYABLE', 'REJECTED_FINAL',
                'RECONCILED', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'ACCEPTED'
            AND NEW.lifecycle_state IN ('RECONCILED', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'REJECTED_RETRYABLE'
            AND NEW.lifecycle_state IN (
                'DISPATCHED', 'REJECTED_FINAL', 'CANCELLED'))
    ) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_TRANSITION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.lifecycle_state = 'REJECTED_FINAL'
       AND NEW.failure_class IS NULL THEN
        RAISE EXCEPTION 'SYS008_FINAL_ITEM_CLASSIFICATION_REQUIRED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'DISPATCHED'
       AND NEW.lifecycle_state IN (
            'ACCEPTED', 'REJECTED_RETRYABLE', 'REJECTED_FINAL')
       AND (NEW.provider_receipt_ref IS NULL
            OR NEW.provider_receipt_digest IS NULL
            OR NEW.provider_evidence_digest IS NULL) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_PROVIDER_EVIDENCE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'RESULT_UNKNOWN'
       AND NEW.lifecycle_state IN (
            'ACCEPTED', 'REJECTED_RETRYABLE', 'REJECTED_FINAL', 'RECONCILED')
       AND (NOT COALESCE(latest_attempt_receipt_matches, FALSE)
            OR NEW.provider_receipt_ref IS NULL
            OR NEW.provider_receipt_digest IS NULL
            OR NEW.reconciliation_digest IS NULL) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_RECONCILIATION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'ACCEPTED'
       AND NEW.lifecycle_state = 'RECONCILED'
       AND (NEW.provider_receipt_ref IS NULL
            OR NEW.provider_receipt_digest IS NULL
            OR NEW.reconciliation_digest IS NULL) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_RECONCILIATION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('PENDING', 'TRANSFORMED')
       AND NEW.lifecycle_state = 'CANCELLED'
       AND parent_state <> 'CANCELLED' THEN
        RAISE EXCEPTION 'SYS008_ITEM_PARENT_CANCEL_REQUIRED'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.latest_attempt_number, NEW.latest_attempt_fence)
       IS DISTINCT FROM
       ROW(OLD.latest_attempt_number, OLD.latest_attempt_fence) THEN
        SELECT EXISTS (
            SELECT 1 FROM sys_connector_execution_attempts attempt
             WHERE attempt.tenant_id = NEW.tenant_id
               AND attempt.execution_id = NEW.execution_id
               AND attempt.execution_item_id = NEW.execution_item_id
               AND attempt.attempt_number = NEW.latest_attempt_number
               AND attempt.attempt_fence = NEW.latest_attempt_fence
               AND attempt.lifecycle_state IN ('READY', 'DISPATCHED'))
          INTO retry_attempt_exists;
        IF NEW.lifecycle_state <> 'DISPATCHED'
           OR OLD.lifecycle_state NOT IN ('TRANSFORMED', 'REJECTED_RETRYABLE')
           OR NEW.latest_attempt_number <> OLD.latest_attempt_number + 1
           OR NEW.latest_attempt_fence <= OLD.latest_attempt_fence
           OR NOT retry_attempt_exists THEN
            RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_ATTEMPT_BINDING_REJECTED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.lifecycle_state IN ('TRANSFORMED', 'REJECTED_RETRYABLE')
       AND NEW.lifecycle_state = 'DISPATCHED'
       AND ROW(NEW.latest_attempt_number, NEW.latest_attempt_fence)
           IS NOT DISTINCT FROM
           ROW(OLD.latest_attempt_number, OLD.latest_attempt_fence) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_ATTEMPT_BINDING_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'REJECTED_RETRYABLE'
       AND NEW.lifecycle_state = 'DISPATCHED' THEN
        SELECT EXISTS (
            SELECT 1 FROM sys_connector_execution_attempts attempt
             WHERE attempt.tenant_id = NEW.tenant_id
               AND attempt.execution_id = NEW.execution_id
               AND attempt.execution_item_id = NEW.execution_item_id
               AND attempt.attempt_number = NEW.latest_attempt_number
               AND attempt.attempt_fence = NEW.latest_attempt_fence
               AND attempt.lifecycle_state IN ('READY', 'DISPATCHED'))
          INTO retry_attempt_exists;
        IF NEW.latest_attempt_number <> OLD.latest_attempt_number + 1
           OR NEW.latest_attempt_fence <= OLD.latest_attempt_fence
           OR NOT retry_attempt_exists THEN
            RAISE EXCEPTION 'SYS008_SUCCESS_REPLAY_OR_RETRY_FENCE_REJECTED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.lifecycle_state = 'REJECTED_RETRYABLE'
       AND NEW.lifecycle_state = 'REJECTED_FINAL'
       AND parent_state <> 'DEAD_LETTER' THEN
        RAISE EXCEPTION 'SYS008_RETRYABLE_ITEM_FINALIZATION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'REJECTED_RETRYABLE'
       AND NEW.lifecycle_state = 'CANCELLED'
       AND parent_state <> 'CANCELLED' THEN
        RAISE EXCEPTION 'SYS008_RETRYABLE_ITEM_CANCEL_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN (
            'DISPATCHED', 'RESULT_UNKNOWN', 'ACCEPTED')
       AND NEW.lifecycle_state = 'CANCELLED' THEN
        SELECT lifecycle_state = 'CANCELLED'
                   AND ((compensation_policy_ref IS NOT NULL
                        AND compensation_receipt_ref IS NOT NULL)
                    OR (provider_failure_conclusive
                        AND NOT unresolved_external_effect))
          INTO parent_compensated
          FROM sys_connector_executions execution
         WHERE execution.tenant_id = NEW.tenant_id
           AND execution.execution_id = NEW.execution_id;
        IF NOT COALESCE(parent_compensated, FALSE) THEN
            RAISE EXCEPTION 'SYS008_EXECUTION_ITEM_COMPENSATION_REQUIRED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_connector_execution_item_guard
BEFORE INSERT OR UPDATE OR DELETE ON sys_connector_execution_items
FOR EACH ROW EXECUTE FUNCTION sys_connector_guard_execution_item();

CREATE OR REPLACE FUNCTION sys_connector_guard_execution_attempt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    maximum_attempt INTEGER;
    maximum_fence BIGINT;
    parent_fence BIGINT;
    parent_revision BIGINT;
    parent_state VARCHAR(24);
    parent_provider_receipt_ref VARCHAR(244);
    parent_provider_receipt_digest CHAR(64);
    item_state VARCHAR(24);
    item_retryable BOOLEAN;
    item_latest_attempt_number INTEGER;
    item_latest_attempt_fence BIGINT;
    parent_compensated BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ATTEMPT_DELETE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    SELECT current_attempt_fence, object_revision, lifecycle_state,
           provider_receipt_ref, provider_receipt_digest
      INTO parent_fence, parent_revision, parent_state,
           parent_provider_receipt_ref, parent_provider_receipt_digest
      FROM sys_connector_executions
     WHERE tenant_id = NEW.tenant_id AND execution_id = NEW.execution_id
     FOR UPDATE;
    SELECT lifecycle_state, retryable_snapshot,
           latest_attempt_number, latest_attempt_fence
      INTO item_state, item_retryable,
           item_latest_attempt_number, item_latest_attempt_fence
      FROM sys_connector_execution_items
     WHERE tenant_id = NEW.tenant_id
       AND execution_id = NEW.execution_id
       AND execution_item_id = NEW.execution_item_id
     FOR UPDATE;
    IF parent_fence IS NULL
       OR (TG_OP = 'INSERT'
            AND NEW.execution_revision_snapshot <> parent_revision)
       OR (TG_OP = 'UPDATE'
            AND NEW.execution_revision_snapshot > parent_revision)
       OR (TG_OP = 'INSERT' AND item_state = 'PENDING'
            AND NEW.attempt_fence > parent_fence)
       OR (TG_OP = 'INSERT' AND item_state = 'REJECTED_RETRYABLE'
            AND NEW.attempt_fence <= parent_fence)
       OR (TG_OP = 'UPDATE' AND NEW.lifecycle_state <> 'CANCELLED'
            AND (NEW.attempt_fence > parent_fence
                OR NEW.attempt_number <> item_latest_attempt_number
                OR NEW.attempt_fence <> item_latest_attempt_fence)) THEN
        RAISE EXCEPTION 'SYS008_STALE_ATTEMPT_FENCE_REJECTED'
            USING ERRCODE = '40001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'READY' OR NEW.object_revision <> 1
           OR NEW.provider_receipt_ref IS NOT NULL
           OR NEW.provider_receipt_digest IS NOT NULL
           OR NEW.response_digest IS NOT NULL
           OR NEW.reconciliation_digest IS NOT NULL
           OR NEW.failure_class IS NOT NULL
           OR NEW.dispatched_at IS NOT NULL
           OR NEW.acknowledged_at IS NOT NULL
           OR NEW.completed_at IS NOT NULL THEN
            RAISE EXCEPTION 'SYS008_EXECUTION_ATTEMPT_INITIAL_STATE_REJECTED'
                USING ERRCODE = '23514';
        END IF;
        IF parent_state IN ('RECONCILED', 'DEAD_LETTER', 'CANCELLED')
           OR item_state IS NULL
           OR NOT ((parent_state IN ('FAILED', 'PARTIAL')
                        AND item_state = 'REJECTED_RETRYABLE'
                        AND item_retryable IS TRUE)
                    OR (NEW.attempt_number = 1
                        AND parent_state IN (
                            'ACCEPTED', 'TRANSFORMING', 'DISPATCHED')
                        AND item_state IN (
                            'PENDING', 'TRANSFORMED', 'DISPATCHED'))) THEN
            RAISE EXCEPTION 'SYS008_EXECUTION_ATTEMPT_PARENT_STATE_REJECTED'
                USING ERRCODE = '23514';
        END IF;
        SELECT COALESCE(MAX(attempt_number), 0), COALESCE(MAX(attempt_fence), 0)
          INTO maximum_attempt, maximum_fence
          FROM sys_connector_execution_attempts
         WHERE tenant_id = NEW.tenant_id
           AND execution_id = NEW.execution_id
           AND execution_item_id = NEW.execution_item_id;
        IF NEW.attempt_number <> maximum_attempt + 1
           OR NEW.attempt_fence <= maximum_fence THEN
            RAISE EXCEPTION 'SYS008_ATTEMPT_MONOTONIC_FENCE_REJECTED'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF parent_state IN ('RECONCILED', 'DEAD_LETTER', 'CANCELLED')
       AND NOT (parent_state = 'CANCELLED'
            AND OLD.lifecycle_state IN (
                'READY', 'DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED')
            AND NEW.lifecycle_state = 'CANCELLED') THEN
        RAISE EXCEPTION 'SYS008_TERMINAL_PARENT_ATTEMPT_MUTATION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.tenant_id, NEW.execution_attempt_id, NEW.public_id,
           NEW.execution_id, NEW.execution_item_id, NEW.attempt_number,
           NEW.attempt_fence, NEW.execution_revision_snapshot,
           NEW.provider_idempotency_key, NEW.request_digest, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.execution_attempt_id, OLD.public_id,
           OLD.execution_id, OLD.execution_item_id, OLD.attempt_number,
           OLD.attempt_fence, OLD.execution_revision_snapshot,
           OLD.provider_idempotency_key, OLD.request_digest, OLD.created_at) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ATTEMPT_SNAPSHOT_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('RECONCILED', 'FAILED', 'CANCELLED')
       AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'SYS008_TERMINAL_EXECUTION_ATTEMPT_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF (ROW(NEW.provider_receipt_ref, NEW.provider_receipt_digest)
            IS DISTINCT FROM
            ROW(OLD.provider_receipt_ref, OLD.provider_receipt_digest)
        AND NOT ((OLD.lifecycle_state = 'DISPATCHED'
                AND NEW.lifecycle_state IN (
                    'RESULT_UNKNOWN', 'ACKNOWLEDGED', 'FAILED'))
            OR (OLD.lifecycle_state = 'RESULT_UNKNOWN'
                AND NEW.lifecycle_state IN ('RECONCILED', 'FAILED')
                AND OLD.provider_receipt_ref IS NULL
                AND OLD.provider_receipt_digest IS NULL
                AND NEW.provider_receipt_ref IS NOT NULL
                AND NEW.provider_receipt_digest IS NOT NULL
                AND NEW.provider_receipt_ref = parent_provider_receipt_ref
                AND NEW.provider_receipt_digest =
                    parent_provider_receipt_digest)))
       OR (NEW.response_digest IS DISTINCT FROM OLD.response_digest
        AND NOT (OLD.lifecycle_state = 'DISPATCHED'
            AND NEW.lifecycle_state IN (
                'RESULT_UNKNOWN', 'ACKNOWLEDGED', 'FAILED')))
       OR (NEW.reconciliation_digest IS DISTINCT FROM
                OLD.reconciliation_digest
        AND NOT (OLD.lifecycle_state IN ('RESULT_UNKNOWN', 'ACKNOWLEDGED')
            AND NEW.lifecycle_state IN ('RECONCILED', 'FAILED')))
       OR (NEW.failure_class IS DISTINCT FROM OLD.failure_class
        AND NOT (OLD.lifecycle_state IN (
                'DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED')
            AND NEW.lifecycle_state = 'FAILED')) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ATTEMPT_EVIDENCE_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF NEW IS DISTINCT FROM OLD
       AND NEW.object_revision <> OLD.object_revision + 1 THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ATTEMPT_CAS_REJECTED'
            USING ERRCODE = '40001';
    END IF;
    IF NEW.lifecycle_state <> OLD.lifecycle_state AND NOT (
        (OLD.lifecycle_state = 'READY'
            AND NEW.lifecycle_state IN ('DISPATCHED', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'DISPATCHED'
            AND NEW.lifecycle_state IN (
                'RESULT_UNKNOWN', 'ACKNOWLEDGED', 'FAILED', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'RESULT_UNKNOWN'
            AND NEW.lifecycle_state IN ('RECONCILED', 'FAILED', 'CANCELLED')) OR
        (OLD.lifecycle_state = 'ACKNOWLEDGED'
            AND NEW.lifecycle_state IN ('RECONCILED', 'FAILED', 'CANCELLED'))
    ) THEN
        RAISE EXCEPTION 'SYS008_EXECUTION_ATTEMPT_TRANSITION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'DISPATCHED'
       AND NEW.lifecycle_state IN ('ACKNOWLEDGED', 'FAILED')
       AND (NEW.provider_receipt_ref IS NULL
            OR NEW.provider_receipt_digest IS NULL
            OR NEW.response_digest IS NULL
            OR (NEW.lifecycle_state = 'FAILED'
                AND NEW.failure_class IS NULL)) THEN
        RAISE EXCEPTION 'SYS008_ATTEMPT_PROVIDER_EVIDENCE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'READY'
       AND NEW.lifecycle_state = 'DISPATCHED'
       AND parent_state <> 'DISPATCHED' THEN
        RAISE EXCEPTION 'SYS008_ATTEMPT_DISPATCH_PARENT_STATE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'RESULT_UNKNOWN'
       AND NEW.lifecycle_state IN ('RECONCILED', 'FAILED')
       AND (NEW.provider_receipt_ref IS DISTINCT FROM
                parent_provider_receipt_ref
            OR NEW.provider_receipt_digest IS DISTINCT FROM
                parent_provider_receipt_digest
            OR NEW.provider_receipt_ref IS NULL
            OR NEW.provider_receipt_digest IS NULL
            OR NEW.reconciliation_digest IS NULL
            OR (NEW.lifecycle_state = 'FAILED'
                AND NEW.failure_class IS NULL)) THEN
        RAISE EXCEPTION 'SYS008_ATTEMPT_SAME_KEY_RECONCILIATION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'ACKNOWLEDGED'
       AND NEW.lifecycle_state = 'RECONCILED'
       AND NEW.reconciliation_digest IS NULL THEN
        RAISE EXCEPTION 'SYS008_ATTEMPT_RECONCILIATION_EVIDENCE_REQUIRED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'ACKNOWLEDGED'
       AND NEW.lifecycle_state = 'FAILED'
       AND (NEW.reconciliation_digest IS NULL
            OR NEW.failure_class IS NULL) THEN
        RAISE EXCEPTION 'SYS008_ATTEMPT_RECONCILIATION_FAILURE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED')
       AND NEW.lifecycle_state = 'CANCELLED' THEN
        SELECT lifecycle_state = 'CANCELLED'
                   AND ((compensation_policy_ref IS NOT NULL
                        AND compensation_receipt_ref IS NOT NULL)
                    OR (provider_failure_conclusive
                        AND NOT unresolved_external_effect))
          INTO parent_compensated
          FROM sys_connector_executions execution
         WHERE execution.tenant_id = NEW.tenant_id
           AND execution.execution_id = NEW.execution_id;
        IF NOT COALESCE(parent_compensated, FALSE) THEN
            RAISE EXCEPTION 'SYS008_EXECUTION_ATTEMPT_COMPENSATION_REQUIRED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.lifecycle_state = 'READY'
       AND NEW.lifecycle_state = 'CANCELLED'
       AND parent_state <> 'CANCELLED' THEN
        RAISE EXCEPTION 'SYS008_ATTEMPT_PARENT_CANCEL_REQUIRED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_connector_execution_attempt_guard
BEFORE INSERT OR UPDATE OR DELETE ON sys_connector_execution_attempts
FOR EACH ROW EXECUTE FUNCTION sys_connector_guard_execution_attempt();

CREATE OR REPLACE FUNCTION sys_connector_verify_attempt_parent_fence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    committed_parent_fence BIGINT;
    committed_parent_revision BIGINT;
    committed_parent_retry_generation INTEGER;
    committed_parent_state VARCHAR(24);
    committed_item_state VARCHAR(24);
    committed_item_retryable BOOLEAN;
    committed_item_attempt_number INTEGER;
    committed_item_attempt_fence BIGINT;
BEGIN
    SELECT current_attempt_fence, object_revision,
           retry_generation, lifecycle_state
      INTO committed_parent_fence, committed_parent_revision,
           committed_parent_retry_generation, committed_parent_state
      FROM sys_connector_executions
     WHERE tenant_id = NEW.tenant_id AND execution_id = NEW.execution_id;
    SELECT lifecycle_state, retryable_snapshot,
           latest_attempt_number, latest_attempt_fence
      INTO committed_item_state, committed_item_retryable,
           committed_item_attempt_number, committed_item_attempt_fence
      FROM sys_connector_execution_items
     WHERE tenant_id = NEW.tenant_id
       AND execution_id = NEW.execution_id
       AND execution_item_id = NEW.execution_item_id;
    IF committed_parent_fence IS NULL
       OR committed_parent_fence < NEW.attempt_fence
       OR committed_parent_revision < NEW.execution_revision_snapshot THEN
        RAISE EXCEPTION 'SYS008_ATTEMPT_PARENT_FENCE_NOT_COMMITTED'
            USING ERRCODE = '40001';
    END IF;
    IF NOT ((committed_parent_state = 'ACCEPTED'
                AND committed_parent_retry_generation = 0
                AND NEW.attempt_number = 1
                AND NEW.lifecycle_state = 'READY'
                AND committed_item_state = 'PENDING'
                AND committed_item_attempt_number = 0
                AND committed_item_attempt_fence = 0)
            OR (committed_item_state = 'DISPATCHED'
                AND committed_item_attempt_number = NEW.attempt_number
                AND committed_item_attempt_fence = NEW.attempt_fence)) THEN
        RAISE EXCEPTION 'SYS008_ATTEMPT_ITEM_BINDING_NOT_COMMITTED'
            USING ERRCODE = '23514';
    END IF;
    IF committed_item_retryable IS TRUE
       AND (committed_parent_state <> 'ACCEPTED'
            OR committed_parent_retry_generation < 1
            OR committed_item_state <> 'DISPATCHED'
            OR committed_item_attempt_number <> NEW.attempt_number
            OR committed_item_attempt_fence <> NEW.attempt_fence) THEN
        RAISE EXCEPTION 'SYS008_RETRY_ATTEMPT_COMMIT_STATE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF committed_item_retryable IS DISTINCT FROM TRUE
       AND NOT (committed_parent_state = 'DISPATCHED'
            OR (committed_parent_state = 'ACCEPTED'
                AND committed_parent_retry_generation = 0
                AND NEW.attempt_number = 1
                AND NEW.lifecycle_state = 'READY'
                AND committed_item_state = 'PENDING')) THEN
        RAISE EXCEPTION 'SYS008_INITIAL_ATTEMPT_COMMIT_STATE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_sys_connector_attempt_parent_fence
AFTER INSERT ON sys_connector_execution_attempts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION sys_connector_verify_attempt_parent_fence();

-- A terminal parent cancellation closes every still-live child in the same
-- transaction. Already-terminal evidence remains untouched and immutable.
CREATE OR REPLACE FUNCTION sys_connector_cascade_execution_cancel()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.lifecycle_state <> 'CANCELLED'
       AND NEW.lifecycle_state = 'CANCELLED' THEN
        UPDATE sys_connector_execution_attempts
           SET lifecycle_state = 'CANCELLED',
               completed_at = COALESCE(completed_at, NEW.completed_at),
               object_revision = object_revision + 1,
               updated_at = NEW.updated_at
         WHERE tenant_id = NEW.tenant_id
           AND execution_id = NEW.execution_id
           AND lifecycle_state IN (
               'READY', 'DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED');
        UPDATE sys_connector_execution_items
           SET lifecycle_state = 'CANCELLED',
               object_revision = object_revision + 1,
               updated_at = NEW.updated_at
         WHERE tenant_id = NEW.tenant_id
           AND execution_id = NEW.execution_id
           AND lifecycle_state IN (
               'PENDING', 'TRANSFORMED', 'DISPATCHED', 'RESULT_UNKNOWN',
               'ACCEPTED', 'REJECTED_RETRYABLE');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_connector_execution_cancel_cascade
AFTER UPDATE ON sys_connector_executions
FOR EACH ROW EXECUTE FUNCTION sys_connector_cascade_execution_cancel();

-- Exhausting retries is terminal for every retryable child. The parent is
-- updated first, then this trigger irreversibly closes the frozen failed set.
CREATE OR REPLACE FUNCTION sys_connector_cascade_execution_dead_letter()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.lifecycle_state IN ('FAILED', 'PARTIAL')
       AND NEW.lifecycle_state = 'DEAD_LETTER' THEN
        UPDATE sys_connector_execution_items
           SET lifecycle_state = 'REJECTED_FINAL',
               object_revision = object_revision + 1,
               updated_at = NEW.updated_at
         WHERE tenant_id = NEW.tenant_id
           AND execution_id = NEW.execution_id
           AND lifecycle_state = 'REJECTED_RETRYABLE'
           AND retryable_snapshot = TRUE;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_connector_execution_dead_letter_cascade
AFTER UPDATE ON sys_connector_executions
FOR EACH ROW EXECUTE FUNCTION sys_connector_cascade_execution_dead_letter();

CREATE OR REPLACE FUNCTION sys_connector_verify_terminal_child_set()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    invalid_child_exists BOOLEAN;
BEGIN
    IF NEW.lifecycle_state = 'RECONCILED' THEN
        SELECT EXISTS (
            SELECT 1 FROM sys_connector_execution_items item
             WHERE item.tenant_id = NEW.tenant_id
               AND item.execution_id = NEW.execution_id
               AND item.lifecycle_state NOT IN (
                   'RECONCILED', 'REJECTED_FINAL', 'CANCELLED')
            UNION ALL
            SELECT 1 FROM sys_connector_execution_attempts attempt
             WHERE attempt.tenant_id = NEW.tenant_id
               AND attempt.execution_id = NEW.execution_id
               AND attempt.lifecycle_state NOT IN (
                   'RECONCILED', 'FAILED', 'CANCELLED'))
          INTO invalid_child_exists;
    ELSIF NEW.lifecycle_state = 'DEAD_LETTER' THEN
        SELECT EXISTS (
            SELECT 1 FROM sys_connector_execution_items item
             WHERE item.tenant_id = NEW.tenant_id
               AND item.execution_id = NEW.execution_id
               AND item.lifecycle_state NOT IN (
                   'ACCEPTED', 'RECONCILED', 'REJECTED_FINAL', 'CANCELLED')
            UNION ALL
            SELECT 1 FROM sys_connector_execution_attempts attempt
             WHERE attempt.tenant_id = NEW.tenant_id
               AND attempt.execution_id = NEW.execution_id
               AND attempt.lifecycle_state NOT IN (
                   'RECONCILED', 'FAILED', 'CANCELLED'))
          INTO invalid_child_exists;
    ELSIF NEW.lifecycle_state = 'CANCELLED' THEN
        SELECT EXISTS (
            SELECT 1 FROM sys_connector_execution_items item
             WHERE item.tenant_id = NEW.tenant_id
               AND item.execution_id = NEW.execution_id
               AND item.lifecycle_state NOT IN (
                   'RECONCILED', 'REJECTED_FINAL', 'CANCELLED')
            UNION ALL
            SELECT 1 FROM sys_connector_execution_attempts attempt
             WHERE attempt.tenant_id = NEW.tenant_id
               AND attempt.execution_id = NEW.execution_id
               AND attempt.lifecycle_state NOT IN (
                   'RECONCILED', 'FAILED', 'CANCELLED'))
          INTO invalid_child_exists;
    ELSE
        RETURN NEW;
    END IF;
    IF invalid_child_exists THEN
        RAISE EXCEPTION 'SYS008_TERMINAL_EXECUTION_CHILD_SET_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_sys_connector_terminal_child_set
AFTER UPDATE ON sys_connector_executions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION sys_connector_verify_terminal_child_set();

-- Existing shared receipts gain the stricter SYS-008 transition and terminal
-- seal only for the exact sixteen owner actions. The V333 SYS-006 trigger and
-- its RESULT_UNKNOWN recovery path remain byte-for-byte and behaviorally
-- unchanged; all other receipt producers pass through this guard unchanged.
ALTER TABLE sys_hris_command_receipts
    ADD CONSTRAINT ck_sys008_receipt_state_tuple CHECK (
        originating_action NOT IN (
            'hris.action.sys.post.platform.v1.admin.hris.connectors',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.test',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.dry-runs',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.executions',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.validate',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.submit',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.approve',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.activate',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.validate',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.submit',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.approve',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.activate',
            'hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.reconcile',
            'hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.retry',
            'hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.cancel')
        OR ((status = 'ACCEPTED'
                AND aggregate_public_id IS NULL
                AND aggregate_revision IS NULL
                AND result_ref IS NULL AND error_code IS NULL
                AND completed_at IS NULL)
            OR (status = 'RUNNING'
                AND error_code IS NULL AND completed_at IS NULL)
            OR (status = 'SUCCEEDED'
                AND aggregate_public_id IS NOT NULL
                AND aggregate_revision IS NOT NULL
                AND aggregate_revision BETWEEN 1 AND 9007199254740991
                AND result_ref IS NOT NULL AND error_code IS NULL
                AND completed_at IS NOT NULL)
            OR (status = 'REJECTED'
                AND aggregate_public_id IS NULL
                AND aggregate_revision IS NULL
                AND result_ref IS NULL AND error_code IS NOT NULL
                AND completed_at IS NOT NULL)
            OR (status = 'FAILED'
                AND error_code IS NOT NULL AND completed_at IS NOT NULL)
            OR (status = 'RESULT_UNKNOWN'
                AND aggregate_public_id IS NOT NULL
                AND aggregate_revision IS NOT NULL
                AND aggregate_revision BETWEEN 1 AND 9007199254740991
                AND error_code IS NOT NULL AND completed_at IS NOT NULL)));

CREATE OR REPLACE FUNCTION sys_integration_guard_shared_receipt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.originating_action IN (
            'hris.action.sys.post.platform.v1.admin.hris.connectors',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.test',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.dry-runs',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.executions',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.validate',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.submit',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.approve',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.activate',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.validate',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.submit',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.approve',
            'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.activate',
            'hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.reconcile',
            'hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.retry',
            'hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.cancel')
           AND NEW.status <> 'ACCEPTED' THEN
            RAISE EXCEPTION 'SYS008_COMMAND_RECEIPT_MUST_START_ACCEPTED'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.originating_action NOT IN (
        'hris.action.sys.post.platform.v1.admin.hris.connectors',
        'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.test',
        'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.dry-runs',
        'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.executions',
        'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.validate',
        'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.submit',
        'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.approve',
        'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.activate',
        'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings',
        'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.validate',
        'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.submit',
        'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.approve',
        'hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.activate',
        'hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.reconcile',
        'hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.retry',
        'hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.cancel') THEN
        RETURN NEW;
    END IF;
    IF OLD.status IN ('SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN')
       AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'SYS008_TERMINAL_COMMAND_RECEIPT_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'ACCEPTED' AND NEW.status = 'RUNNING') OR
        (OLD.status = 'RUNNING' AND NEW.status IN (
            'SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN'))
    ) THEN
        RAISE EXCEPTION 'SYS008_COMMAND_RECEIPT_TRANSITION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_integration_shared_receipt
BEFORE INSERT OR UPDATE ON sys_hris_command_receipts
FOR EACH ROW EXECUTE FUNCTION sys_integration_guard_shared_receipt();

-- Fail closed unless a trusted transaction has set dwp.tenant_id locally.
ALTER TABLE sys_connector_definitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_connector_definitions FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_connector_definition_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_connector_definition_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_connector_mapping_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_connector_mapping_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_connector_executions ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_connector_executions FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_connector_execution_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_connector_execution_items FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_connector_execution_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_connector_execution_attempts FORCE ROW LEVEL SECURITY;

CREATE POLICY sys_connector_definitions_tenant ON sys_connector_definitions
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY sys_connector_definition_versions_tenant
    ON sys_connector_definition_versions
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY sys_connector_mapping_versions_tenant
    ON sys_connector_mapping_versions
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY sys_connector_executions_tenant ON sys_connector_executions
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY sys_connector_execution_items_tenant
    ON sys_connector_execution_items
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY sys_connector_execution_attempts_tenant
    ON sys_connector_execution_attempts
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);

-- No automatic runtime grants are made by this migration. PUBLIC is denied;
-- deployment-provisioned runtime roles must remain NOINHERIT/NOBYPASSRLS and
-- receive explicit least-privilege grants outside the Flyway transaction.
REVOKE ALL ON TABLE sys_connector_definitions FROM PUBLIC;
REVOKE ALL ON TABLE sys_connector_definition_versions FROM PUBLIC;
REVOKE ALL ON TABLE sys_connector_mapping_versions FROM PUBLIC;
REVOKE ALL ON TABLE sys_connector_executions FROM PUBLIC;
REVOKE ALL ON TABLE sys_connector_execution_items FROM PUBLIC;
REVOKE ALL ON TABLE sys_connector_execution_attempts FROM PUBLIC;

COMMENT ON TABLE sys_connector_definitions IS
    'SYS-008 logical connector identity, active-version pointer and CAS revision.';
COMMENT ON TABLE sys_connector_definition_versions IS
    'SYS-008 immutable connector definition subject and append-only lifecycle evidence.';
COMMENT ON TABLE sys_connector_mapping_versions IS
    'SYS-008 immutable typed mapping subject and append-only lifecycle evidence.';
COMMENT ON TABLE sys_connector_executions IS
    'SYS-008 receipt-first execution with frozen provider and authorization evidence.';
COMMENT ON TABLE sys_connector_execution_items IS
    'SYS-008 opaque item digest and state evidence without employee identity.';
COMMENT ON TABLE sys_connector_execution_attempts IS
    'SYS-008 monotonic attempt fence and same-provider-key result evidence.';
