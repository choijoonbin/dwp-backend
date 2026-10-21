-- BASE-TFR-SYS-006: Platform-owned HRIS automation control plane.
-- This migration creates exactly the eight leased SYS-006 tables. Command
-- receipts, domain-event delivery and audit evidence reuse the existing shared
-- Platform stores; no owner-domain payload or arbitrary executable target is
-- persisted here.

CREATE TABLE sys_automation_definitions (
    definition_id UUID NOT NULL DEFAULT gen_random_uuid(),
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    automation_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    owner_domain VARCHAR(8) NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    active_version_number BIGINT,
    subject_revision BIGINT NOT NULL DEFAULT 1,
    object_revision BIGINT NOT NULL DEFAULT 1,
    author_actor_ref UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, definition_id),
    CONSTRAINT uq_sys_automation_definition_public
        UNIQUE (tenant_id, public_id),
    CONSTRAINT uq_sys_automation_definition_key
        UNIQUE (tenant_id, automation_key),
    CONSTRAINT ck_sys_automation_definition_tenant
        CHECK (tenant_id > 0),
    CONSTRAINT ck_sys_automation_definition_key
        CHECK (automation_key ~ '^[a-z][a-z0-9.-]{2,63}$'),
    CONSTRAINT ck_sys_automation_definition_owner
        CHECK (owner_domain IN ('HRM', 'PER', 'TIM', 'PAY')),
    CONSTRAINT ck_sys_automation_definition_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_sys_automation_definition_revisions
        CHECK (subject_revision BETWEEN 1 AND 9007199254740991
            AND object_revision BETWEEN 1 AND 9007199254740991
            AND (active_version_number IS NULL OR active_version_number
                BETWEEN 1 AND 9007199254740991)),
    CONSTRAINT ck_sys_automation_definition_derived_state
        CHECK ((lifecycle_state = 'ACTIVE') = (active_version_number IS NOT NULL)),
    CONSTRAINT ck_sys_automation_definition_text
        CHECK (length(display_name) BETWEEN 1 AND 120
            AND (description IS NULL OR length(description) BETWEEN 1 AND 1000))
);

CREATE TABLE sys_automation_versions (
    version_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    definition_id UUID NOT NULL,
    version_number BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    definition_payload JSONB NOT NULL,
    definition_digest CHAR(64) NOT NULL,
    execution_policy JSONB NOT NULL,
    execution_policy_digest CHAR(64) NOT NULL,
    manual_concurrency_key_template VARCHAR(300) NOT NULL,
    compensation_policy_ref VARCHAR(240),
    compensation_policy_digest CHAR(64) NOT NULL,
    manifest_retry_mode VARCHAR(24) NOT NULL,
    manifest_max_attempts SMALLINT NOT NULL,
    handler_owner_service VARCHAR(128) NOT NULL,
    handler_key VARCHAR(128) NOT NULL,
    handler_contract_version TEXT NOT NULL,
    allowed_scope_types TEXT[] NOT NULL,
    handler_manifest_state VARCHAR(16) NOT NULL,
    handler_manifest_revision BIGINT NOT NULL,
    handler_manifest_digest CHAR(64) NOT NULL,
    handler_signature_key_id VARCHAR(160) NOT NULL,
    handler_signature VARCHAR(2048) NOT NULL,
    handler_signature_digest CHAR(64) NOT NULL,
    input_schema_ref VARCHAR(512) NOT NULL,
    input_schema_digest CHAR(64) NOT NULL,
    receipt_schema_ref VARCHAR(512) NOT NULL,
    receipt_schema_digest CHAR(64) NOT NULL,
    validation_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    validated_by_actor_ref UUID,
    validated_at TIMESTAMPTZ,
    validation_receipt_id UUID,
    validation_evidence_digest CHAR(64),
    compatibility_baseline_version VARCHAR(120),
    compatibility_baseline_digest CHAR(64),
    submitted_by_actor_ref UUID,
    submitted_at TIMESTAMPTZ,
    submitted_subject_revision BIGINT,
    submission_receipt_id UUID,
    submission_evidence_digest CHAR(64),
    approved_by_actor_ref UUID,
    approved_at TIMESTAMPTZ,
    approved_subject_revision BIGINT,
    approval_receipt_id UUID,
    approval_evidence_digest CHAR(64),
    approval_step_up_evidence_digest CHAR(64),
    activated_by_actor_ref UUID,
    activated_at TIMESTAMPTZ,
    activation_receipt_id UUID,
    activation_evidence_digest CHAR(64),
    subject_revision BIGINT NOT NULL DEFAULT 1,
    object_revision BIGINT NOT NULL DEFAULT 1,
    author_actor_ref UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, version_id),
    CONSTRAINT uq_sys_automation_version_number
        UNIQUE (tenant_id, definition_id, version_number),
    CONSTRAINT uq_sys_automation_version_composite
        UNIQUE (tenant_id, definition_id, version_id, version_number),
    CONSTRAINT fk_sys_automation_version_definition
        FOREIGN KEY (tenant_id, definition_id)
        REFERENCES sys_automation_definitions (tenant_id, definition_id),
    CONSTRAINT ck_sys_automation_version_numbers
        CHECK (tenant_id > 0
            AND version_number BETWEEN 1 AND 9007199254740991
            AND handler_manifest_revision BETWEEN 1 AND 9007199254740991
            AND subject_revision BETWEEN 1 AND 9007199254740991
            AND object_revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_sys_automation_version_state
        CHECK (lifecycle_state IN (
            'DRAFT', 'VALIDATED', 'PENDING_APPROVAL', 'APPROVED',
            'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_sys_automation_version_validation_state
        CHECK (validation_state IN ('PENDING', 'VALID', 'INVALID', 'REVOKED')),
    CONSTRAINT ck_sys_automation_version_retry
        CHECK (manifest_retry_mode IN ('NONE', 'FAILED_ITEMS_ONLY', 'WHOLE_INVOCATION')
            AND manifest_max_attempts BETWEEN 1 AND 20),
    CONSTRAINT ck_sys_automation_version_json
        CHECK (jsonb_typeof(definition_payload) = 'object'
            AND jsonb_typeof(execution_policy) = 'object'),
    CONSTRAINT ck_sys_automation_version_digests
        CHECK (definition_digest ~ '^[a-f0-9]{64}$'
            AND execution_policy_digest ~ '^[a-f0-9]{64}$'
            AND compensation_policy_digest ~ '^[a-f0-9]{64}$'
            AND handler_manifest_digest ~ '^[a-f0-9]{64}$'
            AND handler_signature_digest ~ '^[a-f0-9]{64}$'
            AND input_schema_digest ~ '^[a-f0-9]{64}$'
            AND receipt_schema_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_sys_automation_version_optional_digests
        CHECK ((validation_evidence_digest IS NULL OR validation_evidence_digest ~ '^[a-f0-9]{64}$')
            AND (compatibility_baseline_digest IS NULL OR compatibility_baseline_digest ~ '^[a-f0-9]{64}$')
            AND (submission_evidence_digest IS NULL OR submission_evidence_digest ~ '^[a-f0-9]{64}$')
            AND (approval_evidence_digest IS NULL OR approval_evidence_digest ~ '^[a-f0-9]{64}$')
            AND (approval_step_up_evidence_digest IS NULL OR approval_step_up_evidence_digest ~ '^[a-f0-9]{64}$')
            AND (activation_evidence_digest IS NULL OR activation_evidence_digest ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_sys_automation_version_validation_tuple
        CHECK ((validated_by_actor_ref IS NULL AND validated_at IS NULL
                AND validation_receipt_id IS NULL AND validation_evidence_digest IS NULL
                AND compatibility_baseline_version IS NULL
                AND compatibility_baseline_digest IS NULL)
            OR (validated_by_actor_ref IS NOT NULL AND validated_at IS NOT NULL
                AND validation_receipt_id IS NOT NULL AND validation_evidence_digest IS NOT NULL
                AND compatibility_baseline_version IS NOT NULL
                AND compatibility_baseline_digest IS NOT NULL)),
    CONSTRAINT ck_sys_automation_version_submission_tuple
        CHECK ((submitted_by_actor_ref IS NULL AND submitted_at IS NULL
                AND submitted_subject_revision IS NULL AND submission_receipt_id IS NULL
                AND submission_evidence_digest IS NULL)
            OR (submitted_by_actor_ref IS NOT NULL AND submitted_at IS NOT NULL
                AND submitted_subject_revision = subject_revision
                AND submission_receipt_id IS NOT NULL
                AND submission_evidence_digest IS NOT NULL)),
    CONSTRAINT ck_sys_automation_version_approval_tuple
        CHECK ((approved_by_actor_ref IS NULL AND approved_at IS NULL
                AND approved_subject_revision IS NULL AND approval_receipt_id IS NULL
                AND approval_evidence_digest IS NULL
                AND approval_step_up_evidence_digest IS NULL)
            OR (approved_by_actor_ref IS NOT NULL AND approved_at IS NOT NULL
                AND approved_subject_revision = subject_revision
                AND approval_receipt_id IS NOT NULL
                AND approval_evidence_digest IS NOT NULL
                AND approval_step_up_evidence_digest IS NOT NULL
                AND approved_by_actor_ref <> author_actor_ref)),
    CONSTRAINT ck_sys_automation_version_activation_tuple
        CHECK ((activated_by_actor_ref IS NULL AND activated_at IS NULL
                AND activation_receipt_id IS NULL AND activation_evidence_digest IS NULL)
            OR (activated_by_actor_ref IS NOT NULL AND activated_at IS NOT NULL
                AND activation_receipt_id IS NOT NULL
                AND activation_evidence_digest IS NOT NULL)),
    CONSTRAINT ck_sys_automation_version_schema_versions
        CHECK (handler_key ~ '^[a-z][a-z0-9._-]{2,127}$'
            AND handler_owner_service ~ '^[a-z][a-z0-9._-]{2,127}$'
            AND handler_contract_version
                ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}[.]v[1-9][0-9]*$'
            AND length(input_schema_ref) BETWEEN 1 AND 512
            AND input_schema_ref ~ '^[A-Za-z0-9][A-Za-z0-9:/._-]*$'
            AND length(receipt_schema_ref) BETWEEN 1 AND 512
            AND receipt_schema_ref ~ '^[A-Za-z0-9][A-Za-z0-9:/._-]*$'
            AND cardinality(allowed_scope_types) BETWEEN 1 AND 6
            AND array_position(allowed_scope_types, NULL) IS NULL
            AND allowed_scope_types <@ ARRAY[
                'GLOBAL', 'TENANT', 'LEGAL_ENTITY', 'ORGANIZATION',
                'POPULATION', 'SUBJECT']::TEXT[]
            AND cardinality(array_positions(allowed_scope_types, 'GLOBAL')) <= 1
            AND cardinality(array_positions(allowed_scope_types, 'TENANT')) <= 1
            AND cardinality(array_positions(
                allowed_scope_types, 'LEGAL_ENTITY')) <= 1
            AND cardinality(array_positions(
                allowed_scope_types, 'ORGANIZATION')) <= 1
            AND cardinality(array_positions(
                allowed_scope_types, 'POPULATION')) <= 1
            AND cardinality(array_positions(allowed_scope_types, 'SUBJECT')) <= 1
            AND handler_manifest_state IN
                ('DRAFT', 'VERIFIED', 'ACTIVE', 'SUSPENDED', 'REVOKED')
            AND length(manual_concurrency_key_template) BETWEEN 1 AND 300)
);

CREATE UNIQUE INDEX uq_sys_automation_one_active_version
    ON sys_automation_versions (tenant_id, definition_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE sys_automation_schedules (
    schedule_id UUID NOT NULL DEFAULT gen_random_uuid(),
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    definition_id UUID NOT NULL,
    version_id UUID NOT NULL,
    pinned_version_number BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    schedule_expression VARCHAR(240) NOT NULL,
    timezone VARCHAR(80) NOT NULL,
    timezone_database_revision VARCHAR(80) NOT NULL,
    misfire_policy VARCHAR(24) NOT NULL DEFAULT 'SKIP_MISSED',
    input_template_ref VARCHAR(240) NOT NULL,
    input_template_digest CHAR(64) NOT NULL,
    concurrency_key_template VARCHAR(300) NOT NULL,
    schedule_policy_digest CHAR(64) NOT NULL,
    submitted_by_actor_ref UUID,
    submitted_at TIMESTAMPTZ,
    submitted_subject_revision BIGINT,
    submission_receipt_id UUID,
    submission_evidence_digest CHAR(64),
    approved_by_actor_ref UUID,
    approved_at TIMESTAMPTZ,
    approved_subject_revision BIGINT,
    approval_receipt_id UUID,
    approval_evidence_digest CHAR(64),
    approval_step_up_evidence_digest CHAR(64),
    activated_by_actor_ref UUID,
    activated_at TIMESTAMPTZ,
    activation_receipt_id UUID,
    activation_evidence_digest CHAR(64),
    subject_revision BIGINT NOT NULL DEFAULT 1,
    object_revision BIGINT NOT NULL DEFAULT 1,
    fire_cursor_revision BIGINT NOT NULL DEFAULT 0,
    next_fire_at TIMESTAMPTZ,
    last_scheduled_instant_utc TIMESTAMPTZ,
    author_actor_ref UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, schedule_id),
    CONSTRAINT uq_sys_automation_schedule_public UNIQUE (tenant_id, public_id),
    CONSTRAINT fk_sys_automation_schedule_definition
        FOREIGN KEY (tenant_id, definition_id)
        REFERENCES sys_automation_definitions (tenant_id, definition_id),
    CONSTRAINT fk_sys_automation_schedule_version
        FOREIGN KEY (
            tenant_id, definition_id, version_id, pinned_version_number)
        REFERENCES sys_automation_versions
            (tenant_id, definition_id, version_id, version_number),
    CONSTRAINT ck_sys_automation_schedule_state
        CHECK (lifecycle_state IN (
            'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_sys_automation_schedule_numbers
        CHECK (tenant_id > 0
            AND pinned_version_number BETWEEN 1 AND 9007199254740991
            AND subject_revision BETWEEN 1 AND 9007199254740991
            AND object_revision BETWEEN 1 AND 9007199254740991
            AND fire_cursor_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_sys_automation_schedule_policy
        CHECK (misfire_policy = 'SKIP_MISSED'
            AND input_template_digest ~ '^[a-f0-9]{64}$'
            AND schedule_policy_digest ~ '^[a-f0-9]{64}$'
            AND length(concurrency_key_template) BETWEEN 1 AND 300),
    CONSTRAINT ck_sys_automation_schedule_submission
        CHECK ((submitted_by_actor_ref IS NULL AND submitted_at IS NULL
                AND submitted_subject_revision IS NULL AND submission_receipt_id IS NULL
                AND submission_evidence_digest IS NULL)
            OR (submitted_by_actor_ref IS NOT NULL AND submitted_at IS NOT NULL
                AND submitted_subject_revision = subject_revision
                AND submission_receipt_id IS NOT NULL
                AND submission_evidence_digest ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_sys_automation_schedule_approval
        CHECK ((approved_by_actor_ref IS NULL AND approved_at IS NULL
                AND approved_subject_revision IS NULL AND approval_receipt_id IS NULL
                AND approval_evidence_digest IS NULL
                AND approval_step_up_evidence_digest IS NULL)
            OR (approved_by_actor_ref IS NOT NULL AND approved_at IS NOT NULL
                AND approved_subject_revision = subject_revision
                AND approval_receipt_id IS NOT NULL
                AND approval_evidence_digest ~ '^[a-f0-9]{64}$'
                AND approval_step_up_evidence_digest ~ '^[a-f0-9]{64}$'
                AND approved_by_actor_ref <> author_actor_ref)),
    CONSTRAINT ck_sys_automation_schedule_activation
        CHECK ((activated_by_actor_ref IS NULL AND activated_at IS NULL
                AND activation_receipt_id IS NULL AND activation_evidence_digest IS NULL)
            OR (activated_by_actor_ref IS NOT NULL AND activated_at IS NOT NULL
                AND activation_receipt_id IS NOT NULL
                AND activation_evidence_digest ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_sys_automation_schedule_cursor
        CHECK (last_scheduled_instant_utc IS NULL
            OR fire_cursor_revision > 0)
);

CREATE TABLE sys_automation_runs (
    run_id UUID NOT NULL DEFAULT gen_random_uuid(),
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    definition_id UUID NOT NULL,
    version_id UUID NOT NULL,
    version_number BIGINT NOT NULL,
    schedule_id UUID,
    scheduled_instant_utc TIMESTAMPTZ,
    schedule_fire_key CHAR(64),
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    selection_mode VARCHAR(24) NOT NULL DEFAULT 'ALL_ITEMS',
    scope_type VARCHAR(40) NOT NULL,
    scope_id UUID,
    requested_at TIMESTAMPTZ NOT NULL,
    requested_by_actor_ref VARCHAR(512) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    correlation_id UUID NOT NULL,
    input_ref VARCHAR(512) NOT NULL,
    input_digest CHAR(64) NOT NULL,
    expected_item_count BIGINT NOT NULL,
    concurrency_key_digest CHAR(64) NOT NULL,
    retry_mode VARCHAR(24) NOT NULL,
    max_attempts SMALLINT NOT NULL,
    current_attempt SMALLINT NOT NULL DEFAULT 1,
    item_total BIGINT NOT NULL DEFAULT 0,
    item_succeeded BIGINT NOT NULL DEFAULT 0,
    item_failed BIGINT NOT NULL DEFAULT 0,
    output_digest CHAR(64),
    command_receipt_id UUID NOT NULL,
    cancellation_requested_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(1000),
    reconciliation_required_reason VARCHAR(80),
    worker_fence_epoch BIGINT NOT NULL DEFAULT 0,
    worker_lease_token UUID,
    worker_leased_until TIMESTAMPTZ,
    worker_lease_fence_snapshot BIGINT,
    worker_control_fence_snapshot BIGINT,
    worker_revoked_at TIMESTAMPTZ,
    reconciliation_fence_epoch BIGINT NOT NULL DEFAULT 0,
    reconciliation_lease_token UUID,
    reconciliation_leased_until TIMESTAMPTZ,
    reconciliation_lease_fence_snapshot BIGINT,
    reconciliation_control_fence_snapshot BIGINT,
    reconciliation_revoked_at TIMESTAMPTZ,
    control_fence_epoch BIGINT NOT NULL DEFAULT 0,
    object_revision BIGINT NOT NULL DEFAULT 1,
    created_by_actor_ref UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, run_id),
    CONSTRAINT uq_sys_automation_run_public UNIQUE (tenant_id, public_id),
    CONSTRAINT fk_sys_automation_run_definition
        FOREIGN KEY (tenant_id, definition_id)
        REFERENCES sys_automation_definitions (tenant_id, definition_id),
    CONSTRAINT fk_sys_automation_run_version
        FOREIGN KEY (tenant_id, definition_id, version_id, version_number)
        REFERENCES sys_automation_versions
            (tenant_id, definition_id, version_id, version_number),
    CONSTRAINT fk_sys_automation_run_schedule
        FOREIGN KEY (tenant_id, schedule_id)
        REFERENCES sys_automation_schedules (tenant_id, schedule_id),
    CONSTRAINT ck_sys_automation_run_state
        CHECK (lifecycle_state IN (
            'QUEUED', 'LEASED', 'RUNNING', 'SUCCEEDED', 'PARTIAL',
            'FAILED', 'CANCELLED', 'DEAD_LETTER')),
    CONSTRAINT ck_sys_automation_run_selection
        CHECK (selection_mode IN ('ALL_ITEMS', 'FAILED_ITEMS_ONLY')),
    CONSTRAINT ck_sys_automation_run_scope
        CHECK (scope_type IN (
            'GLOBAL', 'TENANT', 'LEGAL_ENTITY', 'ORGANIZATION',
            'POPULATION', 'SUBJECT')),
    CONSTRAINT ck_sys_automation_run_retry
        CHECK (retry_mode IN ('NONE', 'FAILED_ITEMS_ONLY', 'WHOLE_INVOCATION')
            AND max_attempts BETWEEN 1 AND 20
            AND current_attempt BETWEEN 1 AND max_attempts),
    CONSTRAINT ck_sys_automation_run_counts
        CHECK (expected_item_count BETWEEN 0 AND 1000000000
            AND item_total BETWEEN 0 AND 1000000000
            AND item_succeeded BETWEEN 0 AND item_total
            AND item_failed BETWEEN 0 AND item_total
            AND item_succeeded + item_failed <= item_total),
    CONSTRAINT ck_sys_automation_run_numbers
        CHECK (tenant_id > 0
            AND version_number BETWEEN 1 AND 9007199254740991
            AND worker_fence_epoch BETWEEN 0 AND 9007199254740991
            AND reconciliation_fence_epoch BETWEEN 0 AND 9007199254740991
            AND control_fence_epoch BETWEEN 0 AND 9007199254740991
            AND object_revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_sys_automation_run_digests
        CHECK (input_digest ~ '^[a-f0-9]{64}$'
            AND concurrency_key_digest ~ '^[a-f0-9]{64}$'
            AND (output_digest IS NULL OR output_digest ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_sys_automation_run_identity
        CHECK (length(requested_by_actor_ref) BETWEEN 1 AND 512
            AND requested_by_actor_ref ~ '^[A-Za-z0-9][A-Za-z0-9:/._-]*$'
            AND idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$'
            AND length(input_ref) BETWEEN 1 AND 512
            AND input_ref ~ '^[A-Za-z0-9][A-Za-z0-9:/._-]*$'),
    CONSTRAINT ck_sys_automation_run_schedule_fire
        CHECK ((schedule_id IS NULL AND scheduled_instant_utc IS NULL
                AND schedule_fire_key IS NULL)
            OR (schedule_id IS NOT NULL AND scheduled_instant_utc IS NOT NULL
                AND schedule_fire_key ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_sys_automation_run_worker_lease
        CHECK ((worker_lease_token IS NULL AND worker_leased_until IS NULL
                AND worker_lease_fence_snapshot IS NULL
                AND worker_control_fence_snapshot IS NULL)
            OR (worker_lease_token IS NOT NULL AND worker_leased_until IS NOT NULL
                AND worker_lease_fence_snapshot = worker_fence_epoch
                AND worker_control_fence_snapshot = control_fence_epoch
                AND worker_revoked_at IS NULL)),
    CONSTRAINT ck_sys_automation_run_reconciliation_lease
        CHECK ((reconciliation_lease_token IS NULL
                AND reconciliation_leased_until IS NULL
                AND reconciliation_lease_fence_snapshot IS NULL
                AND reconciliation_control_fence_snapshot IS NULL)
            OR (reconciliation_lease_token IS NOT NULL
                AND reconciliation_leased_until IS NOT NULL
                AND reconciliation_lease_fence_snapshot = reconciliation_fence_epoch
                AND reconciliation_control_fence_snapshot = control_fence_epoch
                AND reconciliation_revoked_at IS NULL)),
    CONSTRAINT ck_sys_automation_run_exclusive_authority
        CHECK (worker_lease_token IS NULL OR reconciliation_lease_token IS NULL),
    CONSTRAINT ck_sys_automation_run_terminal
        CHECK ((lifecycle_state IN ('SUCCEEDED', 'CANCELLED', 'DEAD_LETTER'))
                = (completed_at IS NOT NULL)
            AND (lifecycle_state NOT IN ('SUCCEEDED', 'CANCELLED', 'DEAD_LETTER')
                OR output_digest IS NOT NULL)),
    CONSTRAINT ck_sys_automation_run_state_counts
        CHECK ((lifecycle_state <> 'SUCCEEDED'
                OR (item_succeeded = item_total AND item_failed = 0))
            AND (lifecycle_state <> 'PARTIAL'
                OR (item_succeeded > 0 AND item_failed > 0
                    AND item_succeeded + item_failed = item_total))
            AND (lifecycle_state <> 'FAILED'
                OR (item_total > 0 AND item_succeeded = 0
                    AND item_failed = item_total))
            AND (lifecycle_state <> 'DEAD_LETTER'
                OR (item_failed > 0
                    AND item_succeeded + item_failed = item_total)))
);

CREATE UNIQUE INDEX uq_sys_automation_run_schedule_fire
    ON sys_automation_runs (tenant_id, schedule_id, schedule_fire_key)
    WHERE schedule_id IS NOT NULL;

CREATE UNIQUE INDEX uq_sys_automation_run_active_concurrency
    ON sys_automation_runs (tenant_id, concurrency_key_digest)
    WHERE lifecycle_state IN ('QUEUED', 'LEASED', 'RUNNING');

CREATE TABLE sys_automation_run_attempts (
    run_attempt_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    attempt_number SMALLINT NOT NULL,
    selection_mode VARCHAR(24) NOT NULL,
    selected_item_count BIGINT NOT NULL,
    selected_item_digest CHAR(64) NOT NULL,
    resume_after_checkpoint_ordinal BIGINT NOT NULL DEFAULT 0,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    worker_fence_epoch BIGINT NOT NULL DEFAULT 0,
    worker_lease_token UUID,
    worker_leased_until TIMESTAMPTZ,
    worker_lease_fence_epoch_snapshot BIGINT,
    worker_control_fence_epoch_snapshot BIGINT,
    worker_lease_revoked_at TIMESTAMPTZ,
    reconciliation_fence_epoch BIGINT NOT NULL DEFAULT 0,
    reconciliation_lease_token UUID,
    reconciliation_leased_until TIMESTAMPTZ,
    reconciliation_lease_fence_epoch_snapshot BIGINT,
    reconciliation_control_fence_epoch_snapshot BIGINT,
    reconciliation_lease_revoked_at TIMESTAMPTZ,
    reconciliation_required_at TIMESTAMPTZ,
    reconciliation_required_reason VARCHAR(80),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, run_attempt_id),
    CONSTRAINT uq_sys_automation_run_attempt
        UNIQUE (tenant_id, run_id, attempt_number),
    CONSTRAINT uq_sys_automation_run_attempt_identity
        UNIQUE (tenant_id, run_id, attempt_number, run_attempt_id),
    CONSTRAINT fk_sys_automation_run_attempt_run
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES sys_automation_runs (tenant_id, run_id),
    CONSTRAINT ck_sys_automation_run_attempt_number
        CHECK (tenant_id > 0 AND attempt_number BETWEEN 1 AND 20
            AND selected_item_count BETWEEN 0 AND 1000000000
            AND resume_after_checkpoint_ordinal BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_sys_automation_run_attempt_selection
        CHECK (selection_mode IN ('ALL_ITEMS', 'FAILED_ITEMS_ONLY')
            AND selected_item_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_sys_automation_run_attempt_state
        CHECK (lifecycle_state IN (
            'QUEUED', 'LEASED', 'RUNNING', 'SUCCEEDED', 'PARTIAL',
            'FAILED', 'CANCELLED', 'DEAD_LETTER')),
    CONSTRAINT ck_sys_automation_run_attempt_fences
        CHECK (worker_fence_epoch BETWEEN 0 AND 9007199254740991
            AND reconciliation_fence_epoch BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_sys_automation_run_attempt_worker_lease
        CHECK ((worker_lease_token IS NULL AND worker_leased_until IS NULL
                AND worker_lease_fence_epoch_snapshot IS NULL
                AND worker_control_fence_epoch_snapshot IS NULL)
            OR (worker_lease_token IS NOT NULL AND worker_leased_until IS NOT NULL
                AND worker_lease_fence_epoch_snapshot = worker_fence_epoch
                AND worker_control_fence_epoch_snapshot IS NOT NULL
                AND worker_lease_revoked_at IS NULL)),
    CONSTRAINT ck_sys_automation_run_attempt_reconciler_lease
        CHECK ((reconciliation_lease_token IS NULL
                AND reconciliation_leased_until IS NULL
                AND reconciliation_lease_fence_epoch_snapshot IS NULL
                AND reconciliation_control_fence_epoch_snapshot IS NULL)
            OR (reconciliation_lease_token IS NOT NULL
                AND reconciliation_leased_until IS NOT NULL
                AND reconciliation_lease_fence_epoch_snapshot
                    = reconciliation_fence_epoch
                AND reconciliation_control_fence_epoch_snapshot IS NOT NULL
                AND reconciliation_lease_revoked_at IS NULL)),
    CONSTRAINT ck_sys_automation_run_attempt_exclusive_lease
        CHECK (worker_lease_token IS NULL OR reconciliation_lease_token IS NULL),
    CONSTRAINT ck_sys_automation_run_attempt_reconciliation
        CHECK ((reconciliation_required_at IS NULL
                AND reconciliation_required_reason IS NULL)
            OR (reconciliation_required_at IS NOT NULL
                AND reconciliation_required_reason IN (
                    'CANCEL_EFFECT_EVIDENCE_REQUIRED',
                    'CANCEL_COMPENSATION_REQUIRED',
                    'LEASE_EXPIRED_EFFECT_EVIDENCE_REQUIRED',
                    'RESULT_UNKNOWN_EVIDENCE_REQUIRED'))),
    CONSTRAINT ck_sys_automation_run_attempt_times
        CHECK ((started_at IS NULL OR started_at >= created_at)
            AND (completed_at IS NULL OR (started_at IS NOT NULL
                AND completed_at >= started_at)))
);

CREATE TABLE sys_automation_run_items (
    run_item_id UUID NOT NULL DEFAULT gen_random_uuid(),
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    item_sequence BIGINT NOT NULL,
    subject_ref VARCHAR(512) NOT NULL,
    subject_reference_digest CHAR(64) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    result_digest CHAR(64),
    error_code VARCHAR(80),
    effect_state VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    effect_idempotency_key VARCHAR(160),
    effect_request_digest CHAR(64),
    effect_receipt_digest CHAR(64),
    compensation_policy_ref VARCHAR(240),
    compensation_policy_digest CHAR(64),
    compensation_state VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED',
    compensation_effect_state VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    compensation_authority_kind VARCHAR(24),
    compensation_dispatch_reconciliation_fence BIGINT,
    compensation_dispatch_control_fence BIGINT,
    compensation_resolution_reconciliation_fence BIGINT,
    compensation_resolution_control_fence BIGINT,
    compensation_resolution_mode VARCHAR(24),
    compensation_idempotency_key VARCHAR(160),
    compensation_request_digest CHAR(64),
    compensation_receipt_id UUID,
    compensation_receipt_digest CHAR(64),
    compensation_trusted_evidence_digest CHAR(64),
    compensation_completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, run_item_id),
    CONSTRAINT uq_sys_automation_run_item_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uq_sys_automation_run_item_sequence
        UNIQUE (tenant_id, run_id, item_sequence),
    CONSTRAINT uq_sys_automation_run_item_identity
        UNIQUE (tenant_id, run_id, run_item_id),
    CONSTRAINT fk_sys_automation_run_item_run
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES sys_automation_runs (tenant_id, run_id),
    CONSTRAINT ck_sys_automation_run_item_sequence
        CHECK (tenant_id > 0 AND item_sequence BETWEEN 1 AND 1000000000
            AND attempt_count BETWEEN 0 AND 20
            AND length(subject_ref) BETWEEN 1 AND 512
            AND subject_ref ~ '^[A-Za-z0-9][A-Za-z0-9:/._-]*$'),
    CONSTRAINT ck_sys_automation_run_item_state
        CHECK (lifecycle_state IN (
            'PENDING', 'LEASED', 'RUNNING', 'SUCCEEDED', 'FAILED_RETRYABLE',
            'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')),
    CONSTRAINT ck_sys_automation_run_item_effect_state
        CHECK (effect_state IN (
            'NOT_STARTED', 'PREPARED', 'NO_EFFECT_CONFIRMED',
            'COMMITTED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_sys_automation_run_item_compensation_state
        CHECK (compensation_state IN (
            'NOT_REQUIRED', 'PENDING', 'RUNNING', 'SUCCEEDED',
            'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_sys_automation_run_item_compensation_effect
        CHECK (compensation_effect_state IN (
            'NOT_STARTED', 'PREPARED', 'COMMITTED', 'RESULT_UNKNOWN')
            AND (compensation_authority_kind IS NULL
                OR compensation_authority_kind = 'RECONCILER')
            AND (compensation_resolution_mode IS NULL
                OR compensation_resolution_mode IN
                    ('DISPATCH_FINALIZE', 'EVIDENCE_RECONCILE'))),
    CONSTRAINT ck_sys_automation_run_item_digests
        CHECK (subject_reference_digest ~ '^[a-f0-9]{64}$'
            AND (result_digest IS NULL OR result_digest ~ '^[a-f0-9]{64}$')
            AND (effect_request_digest IS NULL OR effect_request_digest ~ '^[a-f0-9]{64}$')
            AND (effect_receipt_digest IS NULL OR effect_receipt_digest ~ '^[a-f0-9]{64}$')
            AND (compensation_policy_digest IS NULL OR compensation_policy_digest ~ '^[a-f0-9]{64}$')
            AND (compensation_request_digest IS NULL OR compensation_request_digest ~ '^[a-f0-9]{64}$')
            AND (compensation_receipt_digest IS NULL OR compensation_receipt_digest ~ '^[a-f0-9]{64}$')
            AND (compensation_trusted_evidence_digest IS NULL
                OR compensation_trusted_evidence_digest ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_sys_automation_run_item_effect_tuple
        CHECK ((effect_state = 'NOT_STARTED' AND effect_idempotency_key IS NULL
                AND effect_request_digest IS NULL AND effect_receipt_digest IS NULL)
            OR (effect_state <> 'NOT_STARTED' AND effect_idempotency_key IS NOT NULL
                AND effect_request_digest IS NOT NULL)),
    CONSTRAINT ck_sys_automation_run_item_compensation_tuple
        CHECK ((compensation_state = 'NOT_REQUIRED'
                AND compensation_idempotency_key IS NULL
                AND compensation_request_digest IS NULL
                AND compensation_receipt_id IS NULL
                AND compensation_receipt_digest IS NULL)
            OR (compensation_state = 'PENDING'
                AND compensation_policy_ref IS NOT NULL
                AND compensation_policy_digest IS NOT NULL
                AND compensation_idempotency_key IS NULL
                AND compensation_request_digest IS NULL
                AND compensation_receipt_id IS NULL
                AND compensation_receipt_digest IS NULL)
            OR (compensation_state IN ('RUNNING', 'SUCCEEDED', 'FAILED',
                    'RESULT_UNKNOWN')
                AND compensation_policy_ref IS NOT NULL
                AND compensation_policy_digest IS NOT NULL
                AND compensation_idempotency_key IS NOT NULL
                AND compensation_request_digest IS NOT NULL)),
    CONSTRAINT ck_sys_automation_run_item_compensation_fences
        CHECK ((compensation_effect_state = 'NOT_STARTED'
                AND compensation_dispatch_reconciliation_fence IS NULL
                AND compensation_dispatch_control_fence IS NULL)
            OR (compensation_effect_state <> 'NOT_STARTED'
                AND compensation_authority_kind = 'RECONCILER'
                AND compensation_dispatch_reconciliation_fence IS NOT NULL
                AND compensation_dispatch_control_fence IS NOT NULL))
        ,
    CONSTRAINT ck_sys_automation_run_item_resolution_fences
        CHECK ((compensation_resolution_mode IS NULL
                AND compensation_resolution_reconciliation_fence IS NULL
                AND compensation_resolution_control_fence IS NULL
                AND compensation_trusted_evidence_digest IS NULL)
            OR (compensation_resolution_mode = 'DISPATCH_FINALIZE'
                AND compensation_resolution_reconciliation_fence
                    = compensation_dispatch_reconciliation_fence
                AND compensation_resolution_control_fence
                    = compensation_dispatch_control_fence
                AND compensation_trusted_evidence_digest IS NULL)
            OR (compensation_resolution_mode = 'EVIDENCE_RECONCILE'
                AND compensation_resolution_reconciliation_fence IS NOT NULL
                AND compensation_resolution_reconciliation_fence
                    <> compensation_dispatch_reconciliation_fence
                AND compensation_resolution_control_fence IS NOT NULL
                AND compensation_trusted_evidence_digest IS NOT NULL)),
    CONSTRAINT ck_sys_automation_run_item_distinct_effect_keys
        CHECK (compensation_idempotency_key IS NULL
            OR compensation_idempotency_key <> effect_idempotency_key)
);

CREATE TABLE sys_automation_item_attempts (
    item_attempt_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    run_item_id UUID NOT NULL,
    attempt_ordinal SMALLINT NOT NULL,
    run_revision_snapshot BIGINT NOT NULL,
    deduped_from_item_attempt_id UUID,
    receipt_state VARCHAR(24) NOT NULL DEFAULT 'ACCEPTED',
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ,
    result_ref VARCHAR(512),
    result_digest CHAR(64),
    error_code VARCHAR(80),
    retryable_snapshot BOOLEAN NOT NULL DEFAULT FALSE,
    correlation_id UUID NOT NULL,
    effect_kind VARCHAR(24) NOT NULL DEFAULT 'ORIGINAL',
    effect_state VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    effect_authority_kind VARCHAR(24),
    effect_idempotency_key VARCHAR(160),
    effect_request_digest CHAR(64),
    effect_receipt_id UUID,
    effect_receipt_digest CHAR(64),
    effect_dispatch_authority_fence BIGINT,
    effect_dispatch_control_fence BIGINT,
    effect_resolution_authority_fence BIGINT,
    effect_resolution_control_fence BIGINT,
    effect_resolution_mode VARCHAR(24),
    effect_trusted_evidence_digest CHAR(64),
    effect_committed_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, item_attempt_id),
    CONSTRAINT uq_sys_automation_item_attempt
        UNIQUE (tenant_id, run_item_id, attempt_ordinal),
    CONSTRAINT uq_sys_automation_effect_idempotency
        UNIQUE (tenant_id, effect_kind, effect_idempotency_key),
    CONSTRAINT fk_sys_automation_item_attempt_run
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES sys_automation_runs (tenant_id, run_id),
    CONSTRAINT fk_sys_automation_item_attempt_item
        FOREIGN KEY (tenant_id, run_id, run_item_id)
        REFERENCES sys_automation_run_items (tenant_id, run_id, run_item_id),
    CONSTRAINT fk_sys_automation_item_attempt_dedupe
        FOREIGN KEY (tenant_id, deduped_from_item_attempt_id)
        REFERENCES sys_automation_item_attempts (tenant_id, item_attempt_id),
    CONSTRAINT ck_sys_automation_item_attempt_numbers
        CHECK (tenant_id > 0 AND attempt_ordinal BETWEEN 1 AND 20
            AND run_revision_snapshot BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_sys_automation_item_attempt_receipt_state
        CHECK (receipt_state IN (
            'ACCEPTED', 'LEASED', 'RUNNING', 'SUCCEEDED',
            'FAILED_RETRYABLE', 'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
            AND (retryable_snapshot = (receipt_state = 'FAILED_RETRYABLE'))),
    CONSTRAINT ck_sys_automation_item_attempt_effect
        CHECK (effect_kind = 'ORIGINAL'
            AND effect_state IN (
                'NOT_STARTED', 'PREPARED', 'NO_EFFECT_CONFIRMED',
                'COMMITTED', 'RESULT_UNKNOWN')
            AND (effect_authority_kind IS NULL
                OR effect_authority_kind IN ('WORKER', 'RECONCILER'))
            AND (effect_resolution_mode IS NULL
                OR effect_resolution_mode IN
                    ('DISPATCH_FINALIZE', 'EVIDENCE_RECONCILE'))),
    CONSTRAINT ck_sys_automation_item_attempt_dispatch
        CHECK ((effect_state = 'NOT_STARTED'
                AND effect_authority_kind IS NULL
                AND effect_idempotency_key IS NULL
                AND effect_request_digest IS NULL
                AND effect_dispatch_authority_fence IS NULL
                AND effect_dispatch_control_fence IS NULL)
            OR (effect_state <> 'NOT_STARTED'
                AND effect_authority_kind IN ('WORKER', 'RECONCILER')
                AND effect_idempotency_key IS NOT NULL
                AND effect_request_digest IS NOT NULL
                AND effect_dispatch_authority_fence IS NOT NULL
                AND effect_dispatch_control_fence IS NOT NULL)),
    CONSTRAINT ck_sys_automation_item_attempt_resolution
        CHECK ((effect_resolution_mode IS NULL
                AND effect_resolution_authority_fence IS NULL
                AND effect_resolution_control_fence IS NULL
                AND effect_trusted_evidence_digest IS NULL)
            OR (effect_resolution_mode = 'DISPATCH_FINALIZE'
                AND effect_resolution_authority_fence
                    = effect_dispatch_authority_fence
                AND effect_resolution_control_fence
                    = effect_dispatch_control_fence
                AND effect_trusted_evidence_digest IS NULL)
            OR (effect_resolution_mode = 'EVIDENCE_RECONCILE'
                AND effect_authority_kind = 'RECONCILER'
                AND effect_resolution_authority_fence IS NOT NULL
                AND effect_resolution_authority_fence
                    <> effect_dispatch_authority_fence
                AND effect_resolution_control_fence IS NOT NULL
                AND effect_trusted_evidence_digest IS NOT NULL)),
    CONSTRAINT ck_sys_automation_item_attempt_digests
        CHECK ((effect_request_digest IS NULL OR effect_request_digest ~ '^[a-f0-9]{64}$')
            AND (effect_receipt_digest IS NULL OR effect_receipt_digest ~ '^[a-f0-9]{64}$')
            AND (effect_trusted_evidence_digest IS NULL
                OR effect_trusted_evidence_digest ~ '^[a-f0-9]{64}$')
            AND (result_digest IS NULL OR result_digest ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_sys_automation_item_attempt_result_ref
        CHECK (result_ref IS NULL OR (
            length(result_ref) BETWEEN 1 AND 512
            AND result_ref ~ '^[A-Za-z0-9][A-Za-z0-9:/._-]*$')),
    CONSTRAINT ck_sys_automation_item_attempt_times
        CHECK ((started_at IS NULL OR started_at >= accepted_at)
            AND (completed_at IS NULL OR (started_at IS NOT NULL
                AND completed_at >= started_at))
            AND (next_attempt_at IS NULL OR completed_at IS NOT NULL))
);

CREATE TABLE sys_automation_run_checkpoints (
    checkpoint_id UUID NOT NULL DEFAULT gen_random_uuid(),
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    run_attempt_id UUID NOT NULL,
    attempt_number SMALLINT NOT NULL,
    checkpoint_ordinal BIGINT NOT NULL,
    processed_item_count BIGINT NOT NULL,
    processed_item_digest CHAR(64) NOT NULL,
    output_digest CHAR(64) NOT NULL,
    writer_authority_kind VARCHAR(24) NOT NULL,
    authority_fence_epoch BIGINT NOT NULL,
    control_fence_epoch BIGINT NOT NULL,
    trusted_effect_receipt_digest CHAR(64),
    committed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, checkpoint_id),
    CONSTRAINT uq_sys_automation_checkpoint_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uq_sys_automation_checkpoint_ordinal
        UNIQUE (tenant_id, run_id, attempt_number, checkpoint_ordinal),
    CONSTRAINT fk_sys_automation_checkpoint_run
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES sys_automation_runs (tenant_id, run_id),
    CONSTRAINT fk_sys_automation_checkpoint_attempt
        FOREIGN KEY (tenant_id, run_id, attempt_number, run_attempt_id)
        REFERENCES sys_automation_run_attempts
            (tenant_id, run_id, attempt_number, run_attempt_id),
    CONSTRAINT ck_sys_automation_checkpoint_numbers
        CHECK (tenant_id > 0 AND attempt_number BETWEEN 1 AND 20
            AND checkpoint_ordinal BETWEEN 1 AND 9007199254740991
            AND processed_item_count BETWEEN 0 AND 1000000000
            AND authority_fence_epoch BETWEEN 0 AND 9007199254740991
            AND control_fence_epoch BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_sys_automation_checkpoint_authority
        CHECK (writer_authority_kind IN ('WORKER', 'RECONCILER')
            AND (writer_authority_kind <> 'RECONCILER'
                OR trusted_effect_receipt_digest ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_sys_automation_checkpoint_digests
        CHECK (processed_item_digest ~ '^[a-f0-9]{64}$'
            AND output_digest ~ '^[a-f0-9]{64}$')
);

-- Definition version activation is the sole writer of the derived definition
-- ACTIVE state and active-version number.
ALTER TABLE sys_automation_definitions
    ADD CONSTRAINT fk_sys_automation_definition_active_version
    FOREIGN KEY (tenant_id, definition_id, active_version_number)
    REFERENCES sys_automation_versions
        (tenant_id, definition_id, version_number)
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX ix_sys_automation_version_catalog
    ON sys_automation_versions
        (tenant_id, definition_id, version_number DESC);
CREATE INDEX ix_sys_automation_schedule_due
    ON sys_automation_schedules (tenant_id, next_fire_at, schedule_id)
    WHERE lifecycle_state = 'ACTIVE';
CREATE INDEX ix_sys_automation_run_state
    ON sys_automation_runs (tenant_id, lifecycle_state, updated_at, run_id);
CREATE INDEX ix_sys_automation_item_run
    ON sys_automation_run_items (tenant_id, run_id, item_sequence);
CREATE INDEX ix_sys_automation_checkpoint_run
    ON sys_automation_run_checkpoints
        (tenant_id, run_id, attempt_number, checkpoint_ordinal);

-- Existing shared receipts gain only the SYS-006 closed transition, terminal
-- immutability and aggregate tuple controls permitted by the active V333 lease.
ALTER TABLE sys_hris_command_receipts
    ADD CONSTRAINT ck_sys006_receipt_aggregate_tuple CHECK (
        originating_action NOT IN (
            'hris.action.sys.post.platform.v1.admin.hris.automations',
            'hris.action.sys.post.platform.v1.admin.hris.automations.resource.runs',
            'hris.action.sys.post.platform.v1.admin.hris.automation-runs.resource.retry',
            'hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions',
            'hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.validate',
            'hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.submit',
            'hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.approve',
            'hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.activate',
            'hris.action.sys.post.platform.v1.admin.hris.automations.resource.schedules',
            'hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.submit',
            'hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.approve',
            'hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.activate',
            'hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.suspend',
            'hris.action.sys.post.platform.v1.admin.hris.automation-runs.resource.cancel')
        OR ((aggregate_public_id IS NULL AND aggregate_revision IS NULL
                AND result_ref IS NULL)
            OR (aggregate_public_id IS NOT NULL
                AND aggregate_revision BETWEEN 1 AND 9007199254740991
                AND result_ref IS NOT NULL)));

CREATE OR REPLACE FUNCTION sys_automation_guard_shared_receipt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.originating_action NOT IN (
        'hris.action.sys.post.platform.v1.admin.hris.automations',
        'hris.action.sys.post.platform.v1.admin.hris.automations.resource.runs',
        'hris.action.sys.post.platform.v1.admin.hris.automation-runs.resource.retry',
        'hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions',
        'hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.validate',
        'hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.submit',
        'hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.approve',
        'hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.activate',
        'hris.action.sys.post.platform.v1.admin.hris.automations.resource.schedules',
        'hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.submit',
        'hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.approve',
        'hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.activate',
        'hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.suspend',
        'hris.action.sys.post.platform.v1.admin.hris.automation-runs.resource.cancel') THEN
        RETURN NEW;
    END IF;
    IF OLD.status IN ('SUCCEEDED', 'REJECTED', 'FAILED') AND
       ROW(NEW.status, NEW.aggregate_public_id, NEW.aggregate_revision,
           NEW.result_ref, NEW.error_code, NEW.completed_at)
       IS DISTINCT FROM
       ROW(OLD.status, OLD.aggregate_public_id, OLD.aggregate_revision,
           OLD.result_ref, OLD.error_code, OLD.completed_at) THEN
        RAISE EXCEPTION 'SYS006_TERMINAL_COMMAND_RECEIPT_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'ACCEPTED' AND NEW.status IN ('RUNNING', 'REJECTED')) OR
        (OLD.status = 'RUNNING' AND NEW.status IN
            ('SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN')) OR
        (OLD.status = 'RESULT_UNKNOWN' AND NEW.status IN
            ('RUNNING', 'SUCCEEDED', 'FAILED'))
    ) THEN
        RAISE EXCEPTION 'SYS006_COMMAND_RECEIPT_TRANSITION_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF (NEW.status = 'ACCEPTED' AND
            (NEW.aggregate_public_id IS NOT NULL OR NEW.error_code IS NOT NULL
                OR NEW.completed_at IS NOT NULL)) OR
       (NEW.status = 'REJECTED' AND
            (NEW.aggregate_public_id IS NOT NULL OR NEW.error_code IS NULL
                OR NEW.completed_at IS NULL)) OR
       (NEW.status = 'SUCCEEDED' AND
            (NEW.aggregate_public_id IS NULL OR NEW.error_code IS NOT NULL
                OR NEW.completed_at IS NULL)) OR
       (NEW.status = 'FAILED' AND
            (NEW.error_code IS NULL OR NEW.completed_at IS NULL)) OR
       (NEW.status = 'RESULT_UNKNOWN' AND
            (NEW.error_code IS NULL OR NEW.completed_at IS NOT NULL)) THEN
        RAISE EXCEPTION 'SYS006_COMMAND_RECEIPT_FIELD_STATE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_automation_shared_receipt
BEFORE UPDATE ON sys_hris_command_receipts
FOR EACH ROW EXECUTE FUNCTION sys_automation_guard_shared_receipt();

-- External effects are admitted only while the exact mutually-exclusive core
-- lease and both persisted fence snapshots are still live at DB statement time.
CREATE OR REPLACE FUNCTION sys_automation_guard_original_effect_authority()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    authority_ok BOOLEAN;
    locked_run_revision BIGINT;
BEGIN
    SELECT object_revision
      INTO locked_run_revision
      FROM sys_automation_runs
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
     FOR UPDATE;
    IF locked_run_revision IS NULL
       OR NEW.run_revision_snapshot > locked_run_revision THEN
        RAISE EXCEPTION 'SYS006_RECEIPT_RUN_REVISION_FUTURE'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.effect_idempotency_key IS NOT NULL
           AND ROW(NEW.effect_kind, NEW.effect_idempotency_key,
                   NEW.effect_request_digest,
                   NEW.effect_dispatch_authority_fence,
                   NEW.effect_dispatch_control_fence)
               IS DISTINCT FROM
               ROW(OLD.effect_kind, OLD.effect_idempotency_key,
                   OLD.effect_request_digest,
                   OLD.effect_dispatch_authority_fence,
                   OLD.effect_dispatch_control_fence) THEN
            RAISE EXCEPTION 'SYS006_ORIGINAL_EFFECT_IDENTITY_IMMUTABLE'
                USING ERRCODE = '23514';
        END IF;
        IF ROW(NEW.effect_state, NEW.effect_authority_kind,
               NEW.effect_idempotency_key, NEW.effect_request_digest,
               NEW.effect_receipt_id, NEW.effect_receipt_digest,
               NEW.effect_dispatch_authority_fence,
               NEW.effect_dispatch_control_fence,
               NEW.effect_resolution_authority_fence,
               NEW.effect_resolution_control_fence,
               NEW.effect_resolution_mode,
               NEW.effect_trusted_evidence_digest,
               NEW.effect_committed_at)
           IS NOT DISTINCT FROM
           ROW(OLD.effect_state, OLD.effect_authority_kind,
               OLD.effect_idempotency_key, OLD.effect_request_digest,
               OLD.effect_receipt_id, OLD.effect_receipt_digest,
               OLD.effect_dispatch_authority_fence,
               OLD.effect_dispatch_control_fence,
               OLD.effect_resolution_authority_fence,
               OLD.effect_resolution_control_fence,
               OLD.effect_resolution_mode,
               OLD.effect_trusted_evidence_digest,
               OLD.effect_committed_at) THEN
            RETURN NEW;
        END IF;
    END IF;
    IF NEW.effect_state = 'PREPARED' THEN
        SELECT EXISTS (
            SELECT 1 FROM sys_automation_run_attempts a
              JOIN sys_automation_runs r
                ON r.tenant_id = a.tenant_id AND r.run_id = a.run_id
               AND r.current_attempt = a.attempt_number
             WHERE a.tenant_id = NEW.tenant_id AND a.run_id = NEW.run_id
               AND a.attempt_number = NEW.attempt_ordinal
               AND a.worker_lease_token IS NOT NULL
               AND r.worker_lease_token = a.worker_lease_token
               AND a.worker_leased_until > statement_timestamp()
               AND r.worker_leased_until > statement_timestamp()
               AND a.worker_fence_epoch = NEW.effect_dispatch_authority_fence
               AND r.worker_fence_epoch = NEW.effect_dispatch_authority_fence
               AND a.worker_lease_fence_epoch_snapshot
                    = NEW.effect_dispatch_authority_fence
               AND a.worker_control_fence_epoch_snapshot
                    = NEW.effect_dispatch_control_fence
               AND r.control_fence_epoch = NEW.effect_dispatch_control_fence
               AND r.reconciliation_lease_token IS NULL
               AND a.reconciliation_lease_token IS NULL)
          INTO authority_ok;
        IF NEW.effect_kind <> 'ORIGINAL'
           OR NEW.effect_authority_kind <> 'WORKER' OR NOT authority_ok THEN
            RAISE EXCEPTION 'SYS006_ORIGINAL_EFFECT_AUTHORITY_REJECTED'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.effect_state IN
        ('COMMITTED', 'NO_EFFECT_CONFIRMED', 'RESULT_UNKNOWN')
       AND NEW.effect_resolution_mode IS NULL THEN
        RAISE EXCEPTION 'SYS006_ORIGINAL_EFFECT_RESOLUTION_REQUIRED'
            USING ERRCODE = '23514';
    ELSIF NEW.effect_resolution_mode = 'DISPATCH_FINALIZE' THEN
        SELECT EXISTS (
            SELECT 1 FROM sys_automation_run_attempts a
              JOIN sys_automation_runs r
                ON r.tenant_id = a.tenant_id AND r.run_id = a.run_id
               AND r.current_attempt = a.attempt_number
             WHERE a.tenant_id = NEW.tenant_id AND a.run_id = NEW.run_id
               AND a.attempt_number = NEW.attempt_ordinal
               AND a.worker_lease_token IS NOT NULL
               AND r.worker_lease_token = a.worker_lease_token
               AND a.worker_leased_until > statement_timestamp()
               AND r.worker_leased_until > statement_timestamp()
               AND a.worker_fence_epoch = NEW.effect_resolution_authority_fence
               AND r.worker_fence_epoch = NEW.effect_resolution_authority_fence
               AND a.worker_control_fence_epoch_snapshot
                    = NEW.effect_resolution_control_fence
               AND r.control_fence_epoch = NEW.effect_resolution_control_fence
               AND r.reconciliation_lease_token IS NULL
               AND a.reconciliation_lease_token IS NULL)
          INTO authority_ok;
        IF NEW.effect_authority_kind <> 'WORKER' OR NOT authority_ok THEN
            RAISE EXCEPTION 'SYS006_ORIGINAL_FINALIZE_AUTHORITY_REJECTED'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.effect_resolution_mode = 'EVIDENCE_RECONCILE' THEN
        SELECT EXISTS (
            SELECT 1 FROM sys_automation_run_attempts a
              JOIN sys_automation_runs r
                ON r.tenant_id = a.tenant_id AND r.run_id = a.run_id
               AND r.current_attempt = a.attempt_number
             WHERE a.tenant_id = NEW.tenant_id AND a.run_id = NEW.run_id
               AND a.attempt_number = NEW.attempt_ordinal
               AND a.worker_lease_token IS NULL
               AND r.worker_lease_token IS NULL
               AND a.reconciliation_lease_token IS NOT NULL
               AND r.reconciliation_lease_token = a.reconciliation_lease_token
               AND a.reconciliation_leased_until > statement_timestamp()
               AND r.reconciliation_leased_until > statement_timestamp()
               AND a.reconciliation_fence_epoch
                    = NEW.effect_resolution_authority_fence
               AND r.reconciliation_fence_epoch
                    = NEW.effect_resolution_authority_fence
               AND a.reconciliation_control_fence_epoch_snapshot
                    = NEW.effect_resolution_control_fence
               AND r.control_fence_epoch = NEW.effect_resolution_control_fence)
          INTO authority_ok;
        IF NEW.effect_authority_kind <> 'RECONCILER'
           OR NEW.effect_trusted_evidence_digest IS NULL OR NOT authority_ok THEN
            RAISE EXCEPTION 'SYS006_ORIGINAL_EVIDENCE_AUTHORITY_REJECTED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_automation_original_effect_authority
BEFORE INSERT OR UPDATE ON sys_automation_item_attempts
FOR EACH ROW EXECUTE FUNCTION sys_automation_guard_original_effect_authority();

CREATE OR REPLACE FUNCTION sys_automation_guard_compensation_authority()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    authority_ok BOOLEAN;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.compensation_idempotency_key IS NOT NULL
           AND ROW(NEW.compensation_policy_ref,
                   NEW.compensation_policy_digest,
                   NEW.compensation_idempotency_key,
                   NEW.compensation_request_digest,
                   NEW.compensation_dispatch_reconciliation_fence,
                   NEW.compensation_dispatch_control_fence)
               IS DISTINCT FROM
               ROW(OLD.compensation_policy_ref,
                   OLD.compensation_policy_digest,
                   OLD.compensation_idempotency_key,
                   OLD.compensation_request_digest,
                   OLD.compensation_dispatch_reconciliation_fence,
                   OLD.compensation_dispatch_control_fence) THEN
            RAISE EXCEPTION 'SYS006_COMPENSATION_IDENTITY_IMMUTABLE'
                USING ERRCODE = '23514';
        END IF;
        IF ROW(NEW.compensation_effect_state,
               NEW.compensation_authority_kind,
               NEW.compensation_policy_ref,
               NEW.compensation_policy_digest,
               NEW.compensation_idempotency_key,
               NEW.compensation_request_digest,
               NEW.compensation_receipt_id,
               NEW.compensation_receipt_digest,
               NEW.compensation_dispatch_reconciliation_fence,
               NEW.compensation_dispatch_control_fence,
               NEW.compensation_resolution_reconciliation_fence,
               NEW.compensation_resolution_control_fence,
               NEW.compensation_resolution_mode,
               NEW.compensation_trusted_evidence_digest,
               NEW.compensation_completed_at)
           IS NOT DISTINCT FROM
           ROW(OLD.compensation_effect_state,
               OLD.compensation_authority_kind,
               OLD.compensation_policy_ref,
               OLD.compensation_policy_digest,
               OLD.compensation_idempotency_key,
               OLD.compensation_request_digest,
               OLD.compensation_receipt_id,
               OLD.compensation_receipt_digest,
               OLD.compensation_dispatch_reconciliation_fence,
               OLD.compensation_dispatch_control_fence,
               OLD.compensation_resolution_reconciliation_fence,
               OLD.compensation_resolution_control_fence,
               OLD.compensation_resolution_mode,
               OLD.compensation_trusted_evidence_digest,
               OLD.compensation_completed_at) THEN
            RETURN NEW;
        END IF;
    END IF;
    IF NEW.compensation_effect_state = 'NOT_STARTED'
       AND NEW.compensation_idempotency_key IS NULL
       AND NEW.compensation_request_digest IS NULL
       AND NEW.compensation_resolution_mode IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.compensation_effect_state IN ('COMMITTED', 'RESULT_UNKNOWN')
       AND NEW.compensation_resolution_mode IS NULL THEN
        RAISE EXCEPTION 'SYS006_COMPENSATION_RESOLUTION_REQUIRED'
            USING ERRCODE = '23514';
    END IF;
    SELECT EXISTS (
        SELECT 1
          FROM sys_automation_run_attempts a
          JOIN sys_automation_runs r
            ON r.tenant_id = a.tenant_id AND r.run_id = a.run_id
           AND r.current_attempt = a.attempt_number
          JOIN sys_automation_versions v
            ON v.tenant_id = r.tenant_id AND v.version_id = r.version_id
         WHERE a.tenant_id = NEW.tenant_id AND a.run_id = NEW.run_id
           AND a.worker_lease_token IS NULL
           AND r.worker_lease_token IS NULL
           AND a.reconciliation_lease_token IS NOT NULL
           AND r.reconciliation_lease_token = a.reconciliation_lease_token
           AND a.reconciliation_leased_until > statement_timestamp()
           AND r.reconciliation_leased_until > statement_timestamp()
           AND a.reconciliation_fence_epoch = CASE
               WHEN NEW.compensation_resolution_mode IS NULL
                   THEN NEW.compensation_dispatch_reconciliation_fence
               ELSE NEW.compensation_resolution_reconciliation_fence END
           AND r.reconciliation_fence_epoch = CASE
               WHEN NEW.compensation_resolution_mode IS NULL
                   THEN NEW.compensation_dispatch_reconciliation_fence
               ELSE NEW.compensation_resolution_reconciliation_fence END
           AND a.reconciliation_control_fence_epoch_snapshot = CASE
               WHEN NEW.compensation_resolution_mode IS NULL
                   THEN NEW.compensation_dispatch_control_fence
               ELSE NEW.compensation_resolution_control_fence END
           AND r.control_fence_epoch = CASE
               WHEN NEW.compensation_resolution_mode IS NULL
                   THEN NEW.compensation_dispatch_control_fence
               ELSE NEW.compensation_resolution_control_fence END
           AND v.compensation_policy_ref = NEW.compensation_policy_ref
           AND v.compensation_policy_digest = NEW.compensation_policy_digest)
      INTO authority_ok;
    IF NEW.compensation_authority_kind <> 'RECONCILER' OR NOT authority_ok THEN
        RAISE EXCEPTION 'SYS006_COMPENSATION_AUTHORITY_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.compensation_resolution_mode = 'EVIDENCE_RECONCILE'
       AND NEW.compensation_trusted_evidence_digest IS NULL THEN
        RAISE EXCEPTION 'SYS006_COMPENSATION_EVIDENCE_REQUIRED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_automation_compensation_authority
BEFORE INSERT OR UPDATE ON sys_automation_run_items
FOR EACH ROW EXECUTE FUNCTION sys_automation_guard_compensation_authority();

CREATE OR REPLACE FUNCTION sys_automation_guard_deduped_item_attempt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    source_ok BOOLEAN;
BEGIN
    IF NEW.deduped_from_item_attempt_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM sys_automation_item_attempts prior
         WHERE prior.tenant_id = NEW.tenant_id
           AND prior.item_attempt_id = NEW.deduped_from_item_attempt_id
           AND prior.run_id = NEW.run_id
           AND prior.run_item_id = NEW.run_item_id
           AND prior.attempt_ordinal < NEW.attempt_ordinal
           AND prior.receipt_state = 'SUCCEEDED'
           AND prior.effect_state = 'COMMITTED'
           AND prior.effect_receipt_digest IS NOT NULL
           AND prior.completed_at IS NOT NULL)
      INTO source_ok;
    IF NEW.receipt_state <> 'SUCCEEDED' OR NEW.completed_at IS NULL
       OR NEW.effect_state <> 'NOT_STARTED' OR NOT source_ok THEN
        RAISE EXCEPTION 'SYS006_DEDUPED_ITEM_EVIDENCE_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_automation_deduped_item_attempt
BEFORE INSERT ON sys_automation_item_attempts
FOR EACH ROW EXECUTE FUNCTION sys_automation_guard_deduped_item_attempt();

CREATE OR REPLACE FUNCTION sys_automation_guard_checkpoint_authority()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    authority_ok BOOLEAN;
    receipt_ok BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1
          FROM sys_automation_run_attempts a
          JOIN sys_automation_runs r
            ON r.tenant_id = a.tenant_id AND r.run_id = a.run_id
           AND r.current_attempt = a.attempt_number
         WHERE a.tenant_id = NEW.tenant_id
           AND a.run_attempt_id = NEW.run_attempt_id
           AND a.run_id = NEW.run_id
           AND a.attempt_number = NEW.attempt_number
           AND ((NEW.writer_authority_kind = 'WORKER'
                 AND a.worker_lease_token IS NOT NULL
                 AND r.worker_lease_token = a.worker_lease_token
                 AND a.worker_leased_until > statement_timestamp()
                 AND r.worker_leased_until > statement_timestamp()
                 AND a.worker_fence_epoch = NEW.authority_fence_epoch
                 AND r.worker_fence_epoch = NEW.authority_fence_epoch
                 AND a.worker_control_fence_epoch_snapshot = NEW.control_fence_epoch
                 AND r.control_fence_epoch = NEW.control_fence_epoch
                 AND a.reconciliation_lease_token IS NULL
                 AND r.reconciliation_lease_token IS NULL)
             OR (NEW.writer_authority_kind = 'RECONCILER'
                 AND a.reconciliation_lease_token IS NOT NULL
                 AND r.reconciliation_lease_token = a.reconciliation_lease_token
                 AND a.reconciliation_leased_until > statement_timestamp()
                 AND r.reconciliation_leased_until > statement_timestamp()
                 AND a.reconciliation_fence_epoch = NEW.authority_fence_epoch
                 AND r.reconciliation_fence_epoch = NEW.authority_fence_epoch
                 AND a.reconciliation_control_fence_epoch_snapshot
                    = NEW.control_fence_epoch
                 AND r.control_fence_epoch = NEW.control_fence_epoch
                 AND a.worker_lease_token IS NULL
                 AND r.worker_lease_token IS NULL)))
      INTO authority_ok;
    IF NOT authority_ok THEN
        RAISE EXCEPTION 'SYS006_CHECKPOINT_AUTHORITY_REJECTED'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.writer_authority_kind = 'RECONCILER' THEN
        SELECT EXISTS (
            SELECT 1 FROM sys_automation_item_attempts ia
             WHERE ia.tenant_id = NEW.tenant_id AND ia.run_id = NEW.run_id
               AND ia.effect_state IN
                   ('COMMITTED', 'NO_EFFECT_CONFIRMED', 'RESULT_UNKNOWN')
               AND (ia.effect_receipt_digest = NEW.trusted_effect_receipt_digest
                    OR ia.effect_trusted_evidence_digest
                       = NEW.trusted_effect_receipt_digest)
            UNION ALL
            SELECT 1 FROM sys_automation_run_items i
             WHERE i.tenant_id = NEW.tenant_id AND i.run_id = NEW.run_id
               AND i.compensation_effect_state IN ('COMMITTED', 'RESULT_UNKNOWN')
               AND (i.compensation_receipt_digest
                       = NEW.trusted_effect_receipt_digest
                    OR i.compensation_trusted_evidence_digest
                       = NEW.trusted_effect_receipt_digest))
          INTO receipt_ok;
        IF NOT receipt_ok THEN
            RAISE EXCEPTION 'SYS006_CHECKPOINT_TRUSTED_RECEIPT_REJECTED'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_automation_checkpoint_authority
BEFORE INSERT ON sys_automation_run_checkpoints
FOR EACH ROW EXECUTE FUNCTION sys_automation_guard_checkpoint_authority();

CREATE OR REPLACE FUNCTION sys_automation_guard_immutable_completion()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SYS006_COMPLETION_FACT_CANNOT_BE_DELETED'
            USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'sys_automation_runs' THEN
        IF OLD.lifecycle_state IN ('SUCCEEDED', 'CANCELLED', 'DEAD_LETTER')
           AND NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'SYS006_TERMINAL_RUN_IMMUTABLE'
                USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'sys_automation_run_attempts' THEN
        IF OLD.completed_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'SYS006_COMPLETED_RUN_ATTEMPT_IMMUTABLE'
                USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'sys_automation_item_attempts' THEN
        IF OLD.completed_at IS NOT NULL
           AND ROW(NEW.tenant_id, NEW.run_id, NEW.run_item_id,
                   NEW.attempt_ordinal, NEW.run_revision_snapshot,
                   NEW.deduped_from_item_attempt_id, NEW.receipt_state,
                   NEW.accepted_at, NEW.next_attempt_at, NEW.result_ref,
                   NEW.result_digest, NEW.error_code, NEW.retryable_snapshot,
                   NEW.correlation_id, NEW.effect_kind,
                   NEW.effect_idempotency_key, NEW.effect_request_digest,
                   NEW.effect_dispatch_authority_fence,
                   NEW.effect_dispatch_control_fence)
               IS DISTINCT FROM
               ROW(OLD.tenant_id, OLD.run_id, OLD.run_item_id,
                   OLD.attempt_ordinal, OLD.run_revision_snapshot,
                   OLD.deduped_from_item_attempt_id, OLD.receipt_state,
                   OLD.accepted_at, OLD.next_attempt_at, OLD.result_ref,
                   OLD.result_digest, OLD.error_code, OLD.retryable_snapshot,
                   OLD.correlation_id, OLD.effect_kind,
                   OLD.effect_idempotency_key, OLD.effect_request_digest,
                   OLD.effect_dispatch_authority_fence,
                   OLD.effect_dispatch_control_fence) THEN
            RAISE EXCEPTION 'SYS006_COMPLETED_ITEM_ATTEMPT_IMMUTABLE'
                USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'sys_automation_run_items' THEN
        IF OLD.lifecycle_state IN
               ('SUCCEEDED', 'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
           AND ROW(NEW.tenant_id, NEW.run_id, NEW.public_id,
                   NEW.item_sequence, NEW.subject_ref,
                   NEW.subject_reference_digest, NEW.lifecycle_state,
                   NEW.attempt_count, NEW.result_digest, NEW.error_code,
                   NEW.effect_idempotency_key, NEW.effect_request_digest,
                   NEW.compensation_policy_ref, NEW.compensation_policy_digest)
               IS DISTINCT FROM
               ROW(OLD.tenant_id, OLD.run_id, OLD.public_id,
                   OLD.item_sequence, OLD.subject_ref,
                   OLD.subject_reference_digest, OLD.lifecycle_state,
                   OLD.attempt_count, OLD.result_digest, OLD.error_code,
                   OLD.effect_idempotency_key, OLD.effect_request_digest,
                   OLD.compensation_policy_ref,
                   OLD.compensation_policy_digest) THEN
            RAISE EXCEPTION 'SYS006_TERMINAL_RUN_ITEM_IMMUTABLE'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_automation_run_terminal
BEFORE UPDATE OR DELETE ON sys_automation_runs
FOR EACH ROW EXECUTE FUNCTION sys_automation_guard_immutable_completion();
CREATE TRIGGER trg_sys_automation_run_attempt_terminal
BEFORE UPDATE OR DELETE ON sys_automation_run_attempts
FOR EACH ROW EXECUTE FUNCTION sys_automation_guard_immutable_completion();
CREATE TRIGGER trg_sys_automation_run_item_terminal
BEFORE UPDATE OR DELETE ON sys_automation_run_items
FOR EACH ROW EXECUTE FUNCTION sys_automation_guard_immutable_completion();
CREATE TRIGGER trg_sys_automation_item_attempt_terminal
BEFORE UPDATE OR DELETE ON sys_automation_item_attempts
FOR EACH ROW EXECUTE FUNCTION sys_automation_guard_immutable_completion();

CREATE OR REPLACE FUNCTION sys_automation_guard_checkpoint_immutable()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'SYS006_CHECKPOINT_IMMUTABLE' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_sys_automation_checkpoint_immutable
BEFORE UPDATE OR DELETE ON sys_automation_run_checkpoints
FOR EACH ROW EXECUTE FUNCTION sys_automation_guard_checkpoint_immutable();

-- Every tenant-owned SYS-006 table fails closed without a verified SET LOCAL
-- dwp.tenant_id. Worker and reconciler transactions use the same tenant fence.
ALTER TABLE sys_automation_definitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_definitions FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_schedules ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_schedules FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_run_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_run_attempts FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_run_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_run_items FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_item_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_item_attempts FORCE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_run_checkpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_automation_run_checkpoints FORCE ROW LEVEL SECURITY;

CREATE POLICY sys_automation_definitions_tenant ON sys_automation_definitions
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY sys_automation_versions_tenant ON sys_automation_versions
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY sys_automation_schedules_tenant ON sys_automation_schedules
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY sys_automation_runs_tenant ON sys_automation_runs
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY sys_automation_run_attempts_tenant ON sys_automation_run_attempts
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY sys_automation_run_items_tenant ON sys_automation_run_items
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY sys_automation_item_attempts_tenant ON sys_automation_item_attempts
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY sys_automation_run_checkpoints_tenant ON sys_automation_run_checkpoints
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);

COMMENT ON TABLE sys_automation_definitions IS
    'SYS-006 non-executable definition identity and metadata.';
COMMENT ON TABLE sys_automation_versions IS
    'SYS-006 immutable signed typed-handler version evidence and lifecycle.';
COMMENT ON TABLE sys_automation_schedules IS
    'SYS-006 approved version-pinned schedule policy and independent fire cursor.';
COMMENT ON TABLE sys_automation_runs IS
    'SYS-006 run aggregate with mutually exclusive worker and reconciler fences.';
