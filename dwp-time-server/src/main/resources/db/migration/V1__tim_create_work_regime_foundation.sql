-- BASE-TFR-TIM-016 / MIGLEASE-HRIS-W1-TIM-001
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE tim_work_regime_versions (
    work_regime_version_id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL, tenant_id BIGINT NOT NULL,
    regime_key VARCHAR(128) NOT NULL, revision BIGINT NOT NULL, display_name VARCHAR(240) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL, arrangement_kind VARCHAR(32) NOT NULL,
    extension_schema_ref VARCHAR(240), extension_schema_version INTEGER, extension_payload JSONB,
    extension_payload_digest CHAR(64), scope_type VARCHAR(32) NOT NULL,
    scope_public_ref VARCHAR(128) NOT NULL, precedence_priority INTEGER NOT NULL DEFAULT 0,
    effective_from DATE NOT NULL, effective_to DATE, default_zone_id VARCHAR(80) NOT NULL,
    rule_pack_public_id UUID NOT NULL, policy_revision BIGINT NOT NULL,
    resolution_digest CHAR(64) NOT NULL, template_schema_version INTEGER NOT NULL,
    template_digest CHAR(64) NOT NULL, author_actor_id BIGINT NOT NULL, approval_actor_id BIGINT,
    approval_receipt_public_id UUID, published_by_actor_id BIGINT, publication_receipt_public_id UUID,
    version BIGINT NOT NULL DEFAULT 1, correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT NOT NULL,
    CONSTRAINT uk_tim_work_regime_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tim_work_regime_tenant_id UNIQUE (tenant_id, work_regime_version_id),
    CONSTRAINT uk_tim_work_regime_tenant_policy UNIQUE (
        tenant_id, work_regime_version_id, rule_pack_public_id, policy_revision ),
    CONSTRAINT uk_tim_work_regime_revision UNIQUE (tenant_id, regime_key, revision),
    CONSTRAINT ck_tim_work_regime_revision CHECK (revision > 0 AND policy_revision > 0 AND version > 0),
    CONSTRAINT ck_tim_work_regime_lifecycle CHECK ( lifecycle_state IN (
            'DRAFT', 'VALIDATED', 'SIMULATED', 'IN_REVIEW', 'APPROVED',
            'PUBLISHED', 'SUPERSEDED', 'RETIRED', 'REJECTED' ) ),
    CONSTRAINT ck_tim_work_regime_arrangement CHECK ( arrangement_kind IN (
            'FIXED', 'FLEX', 'AVERAGED', 'SELECTIVE', 'COMPRESSED',
            'PART_TIME', 'REDUCED', 'SPLIT_SHIFT', 'SHIFT', 'ON_CALL',
            'DEEMED', 'DISCRETIONARY', 'TENANT_EXTENSION' ) ),
    CONSTRAINT ck_tim_work_regime_tenant_extension CHECK ( (arrangement_kind = 'TENANT_EXTENSION'
            AND extension_schema_ref IS NOT NULL AND btrim(extension_schema_ref) <> ''
            AND extension_schema_version IS NOT NULL AND extension_schema_version > 0
            AND extension_payload IS NOT NULL AND jsonb_typeof(extension_payload) = 'object'
            AND extension_payload_digest IS NOT NULL AND extension_payload_digest ~ '^[0-9a-f]{64}$')
        OR (arrangement_kind <> 'TENANT_EXTENSION' AND extension_schema_ref IS NULL
            AND extension_schema_version IS NULL AND extension_payload IS NULL
            AND extension_payload_digest IS NULL) ), CONSTRAINT ck_tim_work_regime_scope CHECK (
        scope_type IN ( 'GLOBAL', 'COUNTRY', 'SUBDIVISION', 'TENANT', 'LEGAL_ENTITY',
            'BUSINESS_UNIT', 'WORKPLACE', 'POPULATION', 'PERSON', 'ASSIGNMENT' ) ),
    CONSTRAINT ck_tim_work_regime_effectivity
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_tim_work_regime_schema CHECK (template_schema_version > 0),
    CONSTRAINT ck_tim_work_regime_digest CHECK ( template_digest ~ '^[0-9a-f]{64}$'
        AND resolution_digest ~ '^[0-9a-f]{64}$' ), CONSTRAINT ck_tim_work_regime_four_eyes CHECK (
        (approval_actor_id IS NULL OR approval_actor_id <> author_actor_id)
        AND (published_by_actor_id IS NULL OR published_by_actor_id <> author_actor_id)
        AND (published_by_actor_id IS NULL OR approval_actor_id IS NULL
            OR published_by_actor_id <> approval_actor_id) ),
    CONSTRAINT ck_tim_work_regime_publish_evidence CHECK (
        lifecycle_state NOT IN ('APPROVED', 'PUBLISHED', 'SUPERSEDED', 'RETIRED')
        OR (approval_actor_id IS NOT NULL AND approval_receipt_public_id IS NOT NULL) ),
    CONSTRAINT ck_tim_work_regime_publisher CHECK (
        lifecycle_state NOT IN ('PUBLISHED', 'SUPERSEDED', 'RETIRED')
        OR (published_by_actor_id IS NOT NULL AND publication_receipt_public_id IS NOT NULL) ),
    CONSTRAINT ck_tim_work_regime_evidence_timing CHECK ( (lifecycle_state IN (
                'DRAFT', 'VALIDATED', 'SIMULATED', 'IN_REVIEW', 'REJECTED' )
            AND approval_actor_id IS NULL AND approval_receipt_public_id IS NULL
            AND published_by_actor_id IS NULL AND publication_receipt_public_id IS NULL)
        OR (lifecycle_state = 'APPROVED' AND approval_actor_id IS NOT NULL
            AND approval_receipt_public_id IS NOT NULL AND published_by_actor_id IS NULL
            AND publication_receipt_public_id IS NULL)
        OR lifecycle_state IN ('PUBLISHED', 'SUPERSEDED', 'RETIRED') )
);
ALTER TABLE tim_work_regime_versions
    ADD CONSTRAINT ex_tim_work_regime_published_period EXCLUDE USING gist ( tenant_id WITH =,
        regime_key WITH =, daterange(effective_from, effective_to, '[)') WITH &&
    ) WHERE (lifecycle_state = 'PUBLISHED');
CREATE INDEX idx_tim_work_regime_resolution
    ON tim_work_regime_versions ( tenant_id, scope_type, scope_public_ref, effective_from,
        precedence_priority DESC, revision DESC ) WHERE lifecycle_state = 'PUBLISHED';
CREATE TABLE tim_work_regime_policy_terms (
    work_regime_policy_term_id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL,
    work_regime_version_id BIGINT NOT NULL, extension_kind VARCHAR(32) NOT NULL,
    parameter_name VARCHAR(100) NOT NULL, value_type VARCHAR(24) NOT NULL, string_value VARCHAR(1024),
    integer_value BIGINT, decimal_value NUMERIC(24, 8), boolean_value BOOLEAN, date_value DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, created_by BIGINT NOT NULL,
    CONSTRAINT fk_tim_work_regime_term_parent FOREIGN KEY (tenant_id, work_regime_version_id)
        REFERENCES tim_work_regime_versions(tenant_id, work_regime_version_id),
    CONSTRAINT uk_tim_work_regime_term
        UNIQUE (tenant_id, work_regime_version_id, extension_kind, parameter_name),
    CONSTRAINT ck_tim_work_regime_term_kind CHECK ( extension_kind IN (
            'FLEXIBLE', 'ELASTIC', 'AVERAGED', 'SELECTIVE', 'DISCRETIONARY',
            'DEEMED', 'REDUCED', 'SHIFT', 'SPLIT_SHIFT', 'ON_CALL', 'OVERTIME', 'BREAK', 'WEEKLY_LIMIT' )
    ), CONSTRAINT ck_tim_work_regime_term_name CHECK (parameter_name ~ '^[A-Z][A-Z0-9_]{1,99}$'),
    CONSTRAINT ck_tim_work_regime_term_value CHECK ( (value_type = 'STRING' AND string_value IS NOT NULL
            AND integer_value IS NULL AND decimal_value IS NULL
            AND boolean_value IS NULL AND date_value IS NULL)
        OR (value_type IN ('INTEGER', 'DURATION_MINUTES') AND integer_value IS NOT NULL
            AND string_value IS NULL AND decimal_value IS NULL
            AND boolean_value IS NULL AND date_value IS NULL)
        OR (value_type = 'DECIMAL' AND decimal_value IS NOT NULL
            AND string_value IS NULL AND integer_value IS NULL
            AND boolean_value IS NULL AND date_value IS NULL)
        OR (value_type = 'BOOLEAN' AND boolean_value IS NOT NULL
            AND string_value IS NULL AND integer_value IS NULL
            AND decimal_value IS NULL AND date_value IS NULL)
        OR (value_type = 'DATE' AND date_value IS NOT NULL
            AND string_value IS NULL AND integer_value IS NULL
            AND decimal_value IS NULL AND boolean_value IS NULL) )
);
CREATE TABLE tim_work_regime_segments (
    work_regime_segment_id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL,
    work_regime_version_id BIGINT NOT NULL, segment_key VARCHAR(160) NOT NULL,
    iso_day_of_week SMALLINT NOT NULL, segment_sequence SMALLINT NOT NULL,
    segment_kind VARCHAR(20) NOT NULL, local_start_time TIME NOT NULL, local_end_time TIME NOT NULL,
    end_day_offset SMALLINT NOT NULL DEFAULT 0, paid BOOLEAN,
    dst_overlap_policy VARCHAR(16) NOT NULL DEFAULT 'REJECT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, created_by BIGINT NOT NULL,
    CONSTRAINT fk_tim_work_regime_segment_parent FOREIGN KEY (tenant_id, work_regime_version_id)
        REFERENCES tim_work_regime_versions(tenant_id, work_regime_version_id),
    CONSTRAINT uk_tim_work_regime_segment
        UNIQUE (tenant_id, work_regime_version_id, iso_day_of_week, segment_sequence),
    CONSTRAINT uk_tim_work_regime_segment_key UNIQUE (tenant_id, work_regime_version_id, segment_key),
    CONSTRAINT ck_tim_work_regime_segment_day CHECK (iso_day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_tim_work_regime_segment_sequence CHECK (segment_sequence > 0),
    CONSTRAINT ck_tim_work_regime_segment_kind
        CHECK (segment_kind IN ('WORK', 'BREAK', 'ON_CALL', 'TRAINING')),
    CONSTRAINT ck_tim_work_regime_segment_day_offset CHECK (end_day_offset IN (0, 1)),
    CONSTRAINT ck_tim_work_regime_segment_duration
        CHECK (end_day_offset = 1 OR local_end_time > local_start_time),
    CONSTRAINT ck_tim_work_regime_segment_minute_precision CHECK (
        EXTRACT(SECOND FROM local_start_time) = 0 AND EXTRACT(SECOND FROM local_end_time) = 0 ),
    CONSTRAINT ck_tim_work_regime_segment_dst
        CHECK (dst_overlap_policy IN ('REJECT', 'EARLIER', 'LATER'))
);
CREATE TABLE tim_rule_pack_versions (
    rule_pack_version_id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL, tenant_id BIGINT NOT NULL,
    pack_key VARCHAR(128) NOT NULL, jurisdiction_country CHAR(2) NOT NULL,
    jurisdiction_subdivision VARCHAR(12) NOT NULL DEFAULT '', policy_revision BIGINT NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL, effective_from DATE NOT NULL, effective_to DATE,
    schema_version INTEGER NOT NULL, schema_digest CHAR(64) NOT NULL, signature_digest CHAR(64) NOT NULL,
    mandatory_bound_digest CHAR(64) NOT NULL, signature_verified BOOLEAN NOT NULL,
    review_status VARCHAR(32) NOT NULL, source_reference VARCHAR(240) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL, CONSTRAINT uk_tim_rule_pack_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tim_rule_pack_public_revision UNIQUE (tenant_id, public_id, policy_revision),
    CONSTRAINT uk_tim_rule_pack_lineage UNIQUE ( tenant_id, public_id,
        jurisdiction_country, jurisdiction_subdivision, policy_revision ),
    CONSTRAINT uk_tim_rule_pack_tenant_id UNIQUE (tenant_id, rule_pack_version_id),
    CONSTRAINT uk_tim_rule_pack_revision UNIQUE (
            tenant_id, jurisdiction_country, jurisdiction_subdivision, policy_revision ),
    CONSTRAINT ck_tim_rule_pack_revision CHECK (policy_revision > 0 AND version > 0),
    CONSTRAINT ck_tim_rule_pack_country CHECK (jurisdiction_country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_tim_rule_pack_lifecycle CHECK ( lifecycle_state IN (
                'STAGED', 'VALIDATED', 'PUBLISHED', 'SUPERSEDED', 'RETIRED', 'REVOKED' ) ),
    CONSTRAINT ck_tim_rule_pack_effectivity
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_tim_rule_pack_schema CHECK (schema_version > 0),
    CONSTRAINT ck_tim_rule_pack_digests CHECK ( schema_digest ~ '^[0-9a-f]{64}$'
        AND signature_digest ~ '^[0-9a-f]{64}$' AND mandatory_bound_digest ~ '^[0-9a-f]{64}$' ),
    CONSTRAINT ck_tim_rule_pack_review
        CHECK (review_status IN ('VERIFIED', 'CUSTOMER_REVIEW_REQUIRED', 'REJECTED')),
    CONSTRAINT ck_tim_rule_pack_publish_verification CHECK ( lifecycle_state <> 'PUBLISHED'
        OR (signature_verified AND review_status <> 'REJECTED') )
);
ALTER TABLE tim_rule_pack_versions
    ADD CONSTRAINT ex_tim_rule_pack_effective_period EXCLUDE USING gist ( tenant_id WITH =,
        jurisdiction_country WITH =, jurisdiction_subdivision WITH =,
        daterange(effective_from, effective_to, '[)') WITH && ) WHERE (lifecycle_state = 'PUBLISHED');
ALTER TABLE tim_work_regime_versions
    ADD CONSTRAINT fk_tim_work_regime_rule_pack
    FOREIGN KEY (tenant_id, rule_pack_public_id, policy_revision)
    REFERENCES tim_rule_pack_versions(tenant_id, public_id, policy_revision);
CREATE TABLE tim_rule_pack_parameters (
    rule_pack_parameter_id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL,
    rule_pack_version_id BIGINT NOT NULL, parameter_name VARCHAR(100) NOT NULL,
    value_type VARCHAR(24) NOT NULL, mandatory BOOLEAN NOT NULL DEFAULT TRUE, string_value VARCHAR(1024),
    integer_value BIGINT, decimal_value NUMERIC(24, 8), boolean_value BOOLEAN, date_value DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, created_by BIGINT NOT NULL,
    CONSTRAINT fk_tim_rule_pack_parameter_parent FOREIGN KEY (tenant_id, rule_pack_version_id)
        REFERENCES tim_rule_pack_versions(tenant_id, rule_pack_version_id),
    CONSTRAINT uk_tim_rule_pack_parameter UNIQUE (tenant_id, rule_pack_version_id, parameter_name),
    CONSTRAINT ck_tim_rule_pack_parameter_name CHECK (parameter_name ~ '^[A-Z][A-Z0-9_]{1,99}$'),
    CONSTRAINT ck_tim_rule_pack_parameter_value CHECK (
        (value_type = 'STRING' AND string_value IS NOT NULL
            AND integer_value IS NULL AND decimal_value IS NULL
            AND boolean_value IS NULL AND date_value IS NULL)
        OR (value_type IN ('INTEGER', 'DURATION_MINUTES') AND integer_value IS NOT NULL
            AND string_value IS NULL AND decimal_value IS NULL
            AND boolean_value IS NULL AND date_value IS NULL)
        OR (value_type = 'DECIMAL' AND decimal_value IS NOT NULL
            AND string_value IS NULL AND integer_value IS NULL
            AND boolean_value IS NULL AND date_value IS NULL)
        OR (value_type = 'BOOLEAN' AND boolean_value IS NOT NULL
            AND string_value IS NULL AND integer_value IS NULL
            AND decimal_value IS NULL AND date_value IS NULL)
        OR (value_type = 'DATE' AND date_value IS NOT NULL
            AND string_value IS NULL AND integer_value IS NULL
            AND decimal_value IS NULL AND boolean_value IS NULL) )
);
CREATE TABLE tim_work_plan_assignments (
    work_plan_assignment_id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL, tenant_id BIGINT NOT NULL,
    worker_public_id UUID NOT NULL, people_assignment_public_id UUID NOT NULL,
    people_assignment_revision BIGINT NOT NULL, work_regime_version_id BIGINT NOT NULL,
    rule_pack_public_id UUID NOT NULL,
    effective_from DATE NOT NULL, effective_to DATE, zone_id VARCHAR(80) NOT NULL,
    jurisdiction_country CHAR(2) NOT NULL, jurisdiction_subdivision VARCHAR(12) NOT NULL DEFAULT '',
    policy_revision BIGINT NOT NULL, lifecycle_state VARCHAR(20) NOT NULL,
    source_context_digest CHAR(64) NOT NULL, version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT NOT NULL,
    CONSTRAINT uk_tim_work_plan_assignment_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tim_work_plan_assignment_tenant_id UNIQUE (tenant_id, work_plan_assignment_id),
    CONSTRAINT uk_tim_work_plan_assignment_tenant_policy UNIQUE (
        tenant_id, work_plan_assignment_id, rule_pack_public_id, policy_revision ),
    CONSTRAINT fk_tim_work_plan_assignment_regime
        FOREIGN KEY (tenant_id, work_regime_version_id, rule_pack_public_id, policy_revision)
        REFERENCES tim_work_regime_versions(
            tenant_id, work_regime_version_id, rule_pack_public_id, policy_revision ),
    CONSTRAINT fk_tim_work_plan_assignment_rule_pack FOREIGN KEY (
            tenant_id, rule_pack_public_id, jurisdiction_country,
            jurisdiction_subdivision, policy_revision
        ) REFERENCES tim_rule_pack_versions(
            tenant_id, public_id, jurisdiction_country,
            jurisdiction_subdivision, policy_revision ),
    CONSTRAINT ck_tim_work_plan_assignment_revision
        CHECK (people_assignment_revision >= 0 AND policy_revision > 0 AND version > 0),
    CONSTRAINT ck_tim_work_plan_assignment_effectivity
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_tim_work_plan_assignment_country CHECK (jurisdiction_country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_tim_work_plan_assignment_state
        CHECK (lifecycle_state IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT ck_tim_work_plan_assignment_digest CHECK (source_context_digest ~ '^[0-9a-f]{64}$')
);
ALTER TABLE tim_work_plan_assignments
    ADD CONSTRAINT ex_tim_work_plan_assignment_period EXCLUDE USING gist ( tenant_id WITH =,
        people_assignment_public_id WITH =, daterange(effective_from, effective_to, '[)') WITH &&
    ) WHERE (lifecycle_state = 'PUBLISHED');
CREATE INDEX idx_tim_work_plan_assignment_worker ON tim_work_plan_assignments
    (tenant_id, worker_public_id, effective_from, effective_to);
CREATE TABLE tim_schedule_simulation_runs (
    schedule_simulation_run_id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL, tenant_id BIGINT NOT NULL,
    work_regime_version_id BIGINT NOT NULL, work_plan_assignment_id BIGINT,
    simulation_from DATE NOT NULL, simulation_to DATE NOT NULL, lifecycle_state VARCHAR(20) NOT NULL,
    command_receipt_public_id UUID NOT NULL, idempotency_key UUID NOT NULL,
    request_digest CHAR(64) NOT NULL, result_digest CHAR(64), zone_id VARCHAR(80) NOT NULL,
    tzdb_version VARCHAR(40) NOT NULL, rule_pack_public_id UUID NOT NULL,
    policy_revision BIGINT NOT NULL, resolution_digest CHAR(64) NOT NULL, calculated_at TIMESTAMPTZ,
    findings JSONB NOT NULL DEFAULT '[]'::jsonb, finding_count INTEGER NOT NULL DEFAULT 0,
    partial_failure_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, created_by BIGINT NOT NULL,
    CONSTRAINT uk_tim_schedule_simulation_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tim_schedule_simulation_tenant_id UNIQUE (tenant_id, schedule_simulation_run_id),
    CONSTRAINT uk_tim_schedule_simulation_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_tim_schedule_simulation_regime
        FOREIGN KEY (tenant_id, work_regime_version_id, rule_pack_public_id, policy_revision)
        REFERENCES tim_work_regime_versions(
            tenant_id, work_regime_version_id, rule_pack_public_id, policy_revision ),
    CONSTRAINT fk_tim_schedule_simulation_assignment
        FOREIGN KEY (tenant_id, work_plan_assignment_id, rule_pack_public_id, policy_revision)
        REFERENCES tim_work_plan_assignments(
            tenant_id, work_plan_assignment_id, rule_pack_public_id, policy_revision ),
    CONSTRAINT fk_tim_schedule_simulation_rule_pack
        FOREIGN KEY (tenant_id, rule_pack_public_id, policy_revision)
        REFERENCES tim_rule_pack_versions(tenant_id, public_id, policy_revision),
    CONSTRAINT ck_tim_schedule_simulation_period CHECK (simulation_to > simulation_from),
    CONSTRAINT ck_tim_schedule_simulation_state CHECK ( lifecycle_state IN (
            'ACCEPTED', 'RUNNING', 'SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN' ) ),
    CONSTRAINT ck_tim_schedule_simulation_policy CHECK (policy_revision > 0),
    CONSTRAINT ck_tim_schedule_simulation_counts
        CHECK (finding_count >= 0 AND partial_failure_count >= 0),
    CONSTRAINT ck_tim_schedule_simulation_findings CHECK (jsonb_typeof(findings) = 'array'),
    CONSTRAINT ck_tim_schedule_simulation_digests CHECK ( request_digest ~ '^[0-9a-f]{64}$'
        AND (result_digest IS NULL OR result_digest ~ '^[0-9a-f]{64}$')
        AND resolution_digest ~ '^[0-9a-f]{64}$' ), CONSTRAINT ck_tim_schedule_simulation_result CHECK (
        (lifecycle_state = 'SUCCEEDED' AND result_digest IS NOT NULL AND calculated_at IS NOT NULL)
        OR lifecycle_state <> 'SUCCEEDED' )
);
CREATE SEQUENCE tim_schedule_simulation_segment_id_seq;
CREATE TABLE tim_schedule_simulation_segments (
    schedule_simulation_segment_id BIGINT PRIMARY KEY
        DEFAULT nextval('tim_schedule_simulation_segment_id_seq'), tenant_id BIGINT NOT NULL,
    schedule_simulation_run_id BIGINT NOT NULL, work_plan_assignment_id BIGINT NOT NULL,
    segment_key VARCHAR(160) NOT NULL, change_kind VARCHAR(16) NOT NULL,
    segment_kind VARCHAR(20) NOT NULL, local_work_date DATE NOT NULL, start_at TIMESTAMPTZ,
    end_at TIMESTAMPTZ, zone_id VARCHAR(80) NOT NULL, start_offset_seconds INTEGER,
    end_offset_seconds INTEGER, overnight BOOLEAN NOT NULL, dst_resolution VARCHAR(20) NOT NULL,
    current_minutes INTEGER, draft_minutes INTEGER, rule_reference VARCHAR(240) NOT NULL,
    CONSTRAINT fk_tim_schedule_simulation_segment_run FOREIGN KEY (tenant_id, schedule_simulation_run_id)
        REFERENCES tim_schedule_simulation_runs(tenant_id, schedule_simulation_run_id),
    CONSTRAINT fk_tim_schedule_simulation_segment_assignment
        FOREIGN KEY (tenant_id, work_plan_assignment_id)
        REFERENCES tim_work_plan_assignments(tenant_id, work_plan_assignment_id),
    CONSTRAINT uk_tim_schedule_simulation_segment UNIQUE ( tenant_id, schedule_simulation_run_id,
            work_plan_assignment_id, local_work_date, segment_key ),
    CONSTRAINT ck_tim_schedule_simulation_segment_change
        CHECK (change_kind IN ('ADDED', 'CHANGED', 'REMOVED', 'UNCHANGED')),
    CONSTRAINT ck_tim_schedule_simulation_segment_kind
        CHECK (segment_kind IN ('WORK', 'BREAK', 'ON_CALL', 'TRAINING')),
    CONSTRAINT ck_tim_schedule_simulation_segment_dst CHECK (
        dst_resolution IN ('EXACT', 'GAP_REJECTED', 'FOLD_EARLIER', 'FOLD_LATER') ),
    CONSTRAINT ck_tim_schedule_simulation_segment_offsets CHECK (
        (start_offset_seconds IS NULL OR start_offset_seconds BETWEEN -64800 AND 64800)
        AND (end_offset_seconds IS NULL OR end_offset_seconds BETWEEN -64800 AND 64800) ),
    CONSTRAINT ck_tim_schedule_simulation_segment_minutes CHECK (
        (current_minutes IS NULL OR current_minutes >= 0)
        AND (draft_minutes IS NULL OR draft_minutes >= 0) ),
    CONSTRAINT ck_tim_schedule_simulation_segment_instants CHECK (
        start_at IS NULL OR end_at IS NULL OR end_at > start_at )
);
ALTER SEQUENCE tim_schedule_simulation_segment_id_seq
    OWNED BY tim_schedule_simulation_segments.schedule_simulation_segment_id;
CREATE TABLE tim_command_receipts (
    command_receipt_id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL, tenant_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL, operation VARCHAR(32) NOT NULL, aggregate_public_id UUID NOT NULL,
    scope_public_ref VARCHAR(128) NOT NULL, expected_version BIGINT,
    request_digest CHAR(64) NOT NULL, lifecycle_state VARCHAR(20) NOT NULL,
    result_code VARCHAR(80), result_digest CHAR(64), actor_id BIGINT NOT NULL,
    purpose_code VARCHAR(80) NOT NULL, authorization_decision_id VARCHAR(128) NOT NULL,
    correlation_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tim_command_receipt_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tim_command_receipt_tenant_id UNIQUE (tenant_id, command_receipt_id),
    CONSTRAINT uk_tim_command_receipt_idempotency UNIQUE (tenant_id, operation, idempotency_key),
    CONSTRAINT ck_tim_command_receipt_operation CHECK ( operation IN (
            'CREATE_DRAFT', 'REVISE_DRAFT', 'VALIDATE', 'SIMULATE',
            'ASSIGN', 'SUBMIT_REVIEW', 'APPLY_APPROVAL', 'PUBLISH' ) ),
    CONSTRAINT ck_tim_command_receipt_version CHECK (
            (operation = 'CREATE_DRAFT' AND expected_version IS NULL) OR (operation <> 'CREATE_DRAFT'
                AND expected_version IS NOT NULL AND expected_version > 0) ),
    CONSTRAINT ck_tim_command_receipt_scope CHECK (
            scope_public_ref <> '' AND scope_public_ref = btrim(scope_public_ref) ),
    CONSTRAINT ck_tim_command_receipt_state CHECK ( lifecycle_state IN (
            'ACCEPTED', 'RUNNING', 'SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN', 'RECONCILING' )
    ), CONSTRAINT ck_tim_command_receipt_digests CHECK ( request_digest ~ '^[0-9a-f]{64}$'
        AND (result_digest IS NULL OR result_digest ~ '^[0-9a-f]{64}$') ),
    CONSTRAINT ck_tim_command_receipt_result CHECK ( (lifecycle_state = 'SUCCEEDED'
            AND result_code IS NOT NULL AND result_digest IS NOT NULL)
        OR (lifecycle_state IN ('REJECTED', 'FAILED', 'RESULT_UNKNOWN') AND result_code IS NOT NULL)
        OR lifecycle_state IN ('ACCEPTED', 'RUNNING', 'RECONCILING') )
);
CREATE INDEX idx_tim_command_receipt_origin
    ON tim_command_receipts (tenant_id, aggregate_public_id, created_at DESC);
ALTER TABLE tim_work_regime_versions
    ADD CONSTRAINT fk_tim_work_regime_approval_receipt
        FOREIGN KEY (tenant_id, approval_receipt_public_id)
        REFERENCES tim_command_receipts(tenant_id, public_id),
    ADD CONSTRAINT fk_tim_work_regime_publication_receipt
        FOREIGN KEY (tenant_id, publication_receipt_public_id)
        REFERENCES tim_command_receipts(tenant_id, public_id);
ALTER TABLE tim_schedule_simulation_runs
    ADD CONSTRAINT fk_tim_schedule_simulation_receipt FOREIGN KEY (tenant_id, command_receipt_public_id)
        REFERENCES tim_command_receipts(tenant_id, public_id);
CREATE UNIQUE INDEX uk_tim_schedule_simulation_receipt ON tim_schedule_simulation_runs (
    tenant_id, command_receipt_public_id);
CREATE TABLE tim_work_regime_audit_events (
    audit_event_id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL, tenant_id BIGINT NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL, aggregate_public_id UUID NOT NULL,
    aggregate_revision BIGINT NOT NULL, event_type VARCHAR(80) NOT NULL, actor_id BIGINT NOT NULL,
    purpose_code VARCHAR(80) NOT NULL, authorization_decision_id VARCHAR(128) NOT NULL,
    before_digest CHAR(64), after_digest CHAR(64) NOT NULL, receipt_public_id UUID NOT NULL,
    correlation_id UUID NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tim_work_regime_audit_public UNIQUE (tenant_id, public_id),
    CONSTRAINT fk_tim_work_regime_audit_receipt FOREIGN KEY (tenant_id, receipt_public_id)
        REFERENCES tim_command_receipts(tenant_id, public_id),
    CONSTRAINT ck_tim_work_regime_audit_revision CHECK (aggregate_revision > 0),
    CONSTRAINT ck_tim_work_regime_audit_digest CHECK (
        (before_digest IS NULL OR before_digest ~ '^[0-9a-f]{64}$') AND after_digest ~ '^[0-9a-f]{64}$' )
);
CREATE TABLE tim_work_regime_outbox_events (
    outbox_event_id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL, tenant_id BIGINT NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL, aggregate_public_id UUID NOT NULL,
    aggregate_revision BIGINT NOT NULL, command_receipt_public_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL, event_payload JSONB NOT NULL, payload_digest CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ, publish_attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_tim_work_regime_outbox_public UNIQUE (tenant_id, public_id),
    CONSTRAINT fk_tim_work_regime_outbox_receipt FOREIGN KEY (tenant_id, command_receipt_public_id)
        REFERENCES tim_command_receipts(tenant_id, public_id),
    CONSTRAINT ck_tim_work_regime_outbox_revision CHECK (aggregate_revision > 0),
    CONSTRAINT ck_tim_work_regime_outbox_payload CHECK ( jsonb_typeof(event_payload) = 'object'
        AND payload_digest ~ '^[0-9a-f]{64}$' AND publish_attempts >= 0 )
);
CREATE INDEX idx_tim_work_regime_outbox_receipt ON tim_work_regime_outbox_events (
    tenant_id, command_receipt_public_id);
CREATE OR REPLACE FUNCTION tim_guard_published_regime_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    evidence_count INTEGER;
    bound_pack_id BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'DRAFT' OR NEW.version <> 1
           OR NEW.approval_actor_id IS NOT NULL
           OR NEW.approval_receipt_public_id IS NOT NULL
           OR NEW.published_by_actor_id IS NOT NULL
           OR NEW.publication_receipt_public_id IS NOT NULL THEN
            RAISE EXCEPTION 'work-regime revisions must start as an unevidenced DRAFT at version one';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.lifecycle_state <> 'DRAFT' THEN
            RAISE EXCEPTION 'non-draft work-regime revisions are immutable';
        END IF;
        RETURN OLD;
    END IF;
    IF NOT (
        NEW.lifecycle_state = OLD.lifecycle_state
        OR (OLD.lifecycle_state = 'DRAFT' AND NEW.lifecycle_state IN ('VALIDATED', 'REJECTED'))
        OR (OLD.lifecycle_state = 'VALIDATED' AND NEW.lifecycle_state IN ('SIMULATED', 'REJECTED'))
        OR (OLD.lifecycle_state = 'SIMULATED' AND NEW.lifecycle_state IN ('IN_REVIEW', 'REJECTED'))
        OR (OLD.lifecycle_state = 'IN_REVIEW' AND NEW.lifecycle_state IN ('APPROVED', 'REJECTED'))
        OR (OLD.lifecycle_state = 'APPROVED' AND NEW.lifecycle_state = 'PUBLISHED')
        OR (OLD.lifecycle_state = 'PUBLISHED' AND NEW.lifecycle_state IN ('SUPERSEDED', 'RETIRED'))
        OR (OLD.lifecycle_state = 'SUPERSEDED' AND NEW.lifecycle_state = 'RETIRED')
    ) THEN
        RAISE EXCEPTION 'invalid work-regime lifecycle transition: % -> %',
            OLD.lifecycle_state, NEW.lifecycle_state;
    END IF;
    IF (OLD.lifecycle_state = 'DRAFT' AND NEW.lifecycle_state = 'VALIDATED')
       OR (OLD.lifecycle_state = 'APPROVED' AND NEW.lifecycle_state = 'PUBLISHED') THEN
        SELECT rp.rule_pack_version_id INTO bound_pack_id
          FROM tim_rule_pack_versions rp
         WHERE rp.tenant_id = NEW.tenant_id
           AND rp.public_id = NEW.rule_pack_public_id
           AND rp.policy_revision = NEW.policy_revision
           AND rp.lifecycle_state = 'PUBLISHED'
           AND rp.signature_verified
           AND rp.review_status <> 'REJECTED'
           AND rp.effective_from <= NEW.effective_from
           AND (rp.effective_to IS NULL OR
                (NEW.effective_to IS NOT NULL AND NEW.effective_to <= rp.effective_to))
         FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'bound rule pack is not publishable for the work-regime period';
        END IF;
    END IF;
    IF OLD.lifecycle_state = 'DRAFT' AND NEW.lifecycle_state = 'VALIDATED' THEN
        PERFORM 1 FROM tim_work_regime_policy_terms t
         WHERE t.tenant_id = NEW.tenant_id
           AND t.work_regime_version_id = NEW.work_regime_version_id
         FOR SHARE;
        PERFORM 1 FROM tim_rule_pack_parameters p
         WHERE p.tenant_id = NEW.tenant_id
           AND p.rule_pack_version_id = bound_pack_id
         FOR SHARE;
        IF EXISTS (
            SELECT 1 FROM tim_rule_pack_parameters p
             WHERE p.tenant_id = NEW.tenant_id
               AND p.rule_pack_version_id = bound_pack_id
               AND p.mandatory
               AND ((SELECT count(*) FROM tim_work_regime_policy_terms t
                      WHERE t.tenant_id = NEW.tenant_id
                        AND t.work_regime_version_id = NEW.work_regime_version_id
                        AND t.parameter_name = p.parameter_name) <> 1
                    OR NOT EXISTS (
                        SELECT 1 FROM tim_work_regime_policy_terms t
                         WHERE t.tenant_id = NEW.tenant_id
                           AND t.work_regime_version_id = NEW.work_regime_version_id
                           AND t.parameter_name = p.parameter_name
                           AND ROW(t.value_type, t.string_value, t.integer_value,
                                   t.decimal_value, t.boolean_value, t.date_value)
                               IS NOT DISTINCT FROM
                               ROW(p.value_type, p.string_value, p.integer_value,
                                   p.decimal_value, p.boolean_value, p.date_value)))
        ) THEN
            RAISE EXCEPTION 'mandatory rule-pack parameters are not bound exactly';
        END IF;
    END IF;
    IF OLD.lifecycle_state = 'IN_REVIEW' AND NEW.lifecycle_state = 'APPROVED' THEN
        SELECT count(*) INTO evidence_count
          FROM tim_command_receipts
         WHERE tenant_id = NEW.tenant_id
           AND public_id = NEW.approval_receipt_public_id
           AND operation = 'APPLY_APPROVAL'
           AND aggregate_public_id = NEW.public_id
           AND actor_id = NEW.approval_actor_id
           AND lifecycle_state IN ('RUNNING', 'SUCCEEDED');
        IF evidence_count <> 1 THEN
            RAISE EXCEPTION 'approval transition requires its matching command receipt';
        END IF;
    END IF;
    IF OLD.lifecycle_state = 'APPROVED' AND NEW.lifecycle_state = 'PUBLISHED' THEN
        SELECT count(*) INTO evidence_count
          FROM tim_command_receipts
         WHERE tenant_id = NEW.tenant_id
           AND public_id = NEW.approval_receipt_public_id
           AND operation = 'APPLY_APPROVAL'
           AND aggregate_public_id = NEW.public_id
           AND actor_id = NEW.approval_actor_id
           AND lifecycle_state = 'SUCCEEDED';
        IF evidence_count <> 1 THEN
            RAISE EXCEPTION 'publish transition requires successful approval evidence';
        END IF;
        SELECT count(*) INTO evidence_count
          FROM tim_command_receipts
         WHERE tenant_id = NEW.tenant_id
           AND public_id = NEW.publication_receipt_public_id
           AND operation = 'PUBLISH'
           AND aggregate_public_id = NEW.public_id
           AND actor_id = NEW.published_by_actor_id
           AND lifecycle_state IN ('RUNNING', 'SUCCEEDED');
        IF evidence_count <> 1 THEN
            RAISE EXCEPTION 'publish transition requires its matching command receipt';
        END IF;
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'work-regime optimistic version must advance exactly once';
    END IF;
    IF (OLD.lifecycle_state <> 'DRAFT' OR NEW.lifecycle_state <> OLD.lifecycle_state)
       AND ROW(
           OLD.tenant_id, OLD.public_id, OLD.regime_key, OLD.revision, OLD.display_name,
           OLD.arrangement_kind, OLD.extension_schema_ref,
           OLD.extension_schema_version, OLD.extension_payload,
           OLD.extension_payload_digest, OLD.scope_type, OLD.scope_public_ref,
           OLD.precedence_priority, OLD.effective_from, OLD.effective_to,
           OLD.default_zone_id, OLD.rule_pack_public_id, OLD.policy_revision,
           OLD.resolution_digest, OLD.template_schema_version, OLD.template_digest,
           OLD.author_actor_id, OLD.correlation_id, OLD.created_at, OLD.created_by
       ) IS DISTINCT FROM ROW(
           NEW.tenant_id, NEW.public_id, NEW.regime_key, NEW.revision, NEW.display_name,
           NEW.arrangement_kind, NEW.extension_schema_ref,
           NEW.extension_schema_version, NEW.extension_payload,
           NEW.extension_payload_digest, NEW.scope_type, NEW.scope_public_ref,
           NEW.precedence_priority, NEW.effective_from, NEW.effective_to,
           NEW.default_zone_id, NEW.rule_pack_public_id, NEW.policy_revision,
           NEW.resolution_digest, NEW.template_schema_version, NEW.template_digest,
           NEW.author_actor_id, NEW.correlation_id, NEW.created_at, NEW.created_by
       ) THEN
        RAISE EXCEPTION 'validated work-regime content is immutable';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_tim_guard_published_regime_revision BEFORE INSERT OR UPDATE OR DELETE ON tim_work_regime_versions
    FOR EACH ROW EXECUTE FUNCTION tim_guard_published_regime_revision();
CREATE OR REPLACE FUNCTION tim_guard_regime_child_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    parent_state VARCHAR(24);
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        SELECT lifecycle_state INTO parent_state
          FROM tim_work_regime_versions
         WHERE tenant_id = OLD.tenant_id
           AND work_regime_version_id = OLD.work_regime_version_id
         FOR SHARE;
        IF parent_state IS DISTINCT FROM 'DRAFT' THEN
            RAISE EXCEPTION 'work-regime child content is mutable only while its parent is DRAFT';
        END IF;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        SELECT lifecycle_state INTO parent_state
          FROM tim_work_regime_versions
         WHERE tenant_id = NEW.tenant_id
           AND work_regime_version_id = NEW.work_regime_version_id
         FOR SHARE;
        IF parent_state IS DISTINCT FROM 'DRAFT' THEN
            RAISE EXCEPTION 'work-regime child content is mutable only while its parent is DRAFT';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$function$;
CREATE TRIGGER trg_tim_guard_regime_policy_term BEFORE INSERT OR UPDATE OR DELETE ON tim_work_regime_policy_terms
    FOR EACH ROW EXECUTE FUNCTION tim_guard_regime_child_mutation();
CREATE TRIGGER trg_tim_guard_regime_segment BEFORE INSERT OR UPDATE OR DELETE ON tim_work_regime_segments
    FOR EACH ROW EXECUTE FUNCTION tim_guard_regime_child_mutation();
CREATE OR REPLACE FUNCTION tim_guard_rule_pack_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'STAGED' OR NEW.version <> 1 THEN
            RAISE EXCEPTION 'rule-pack revisions must start STAGED at version one';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.lifecycle_state <> 'STAGED' THEN
            RAISE EXCEPTION 'validated rule-pack revisions are immutable';
        END IF;
        RETURN OLD;
    END IF;
    IF NOT (
        NEW.lifecycle_state = OLD.lifecycle_state
        OR (OLD.lifecycle_state = 'STAGED' AND NEW.lifecycle_state IN ('VALIDATED', 'REVOKED'))
        OR (OLD.lifecycle_state = 'VALIDATED' AND NEW.lifecycle_state IN ('PUBLISHED', 'REVOKED'))
        OR (OLD.lifecycle_state = 'PUBLISHED'
            AND NEW.lifecycle_state IN ('SUPERSEDED', 'RETIRED', 'REVOKED'))
        OR (OLD.lifecycle_state = 'SUPERSEDED' AND NEW.lifecycle_state IN ('RETIRED', 'REVOKED'))
    ) THEN
        RAISE EXCEPTION 'invalid rule-pack lifecycle transition: % -> %',
            OLD.lifecycle_state, NEW.lifecycle_state;
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'rule-pack optimistic version must advance exactly once';
    END IF;
    IF (OLD.lifecycle_state <> 'STAGED' OR NEW.lifecycle_state <> OLD.lifecycle_state)
       AND ROW(
           OLD.tenant_id, OLD.public_id, OLD.pack_key, OLD.jurisdiction_country,
           OLD.jurisdiction_subdivision, OLD.policy_revision, OLD.effective_from,
           OLD.effective_to, OLD.schema_version, OLD.schema_digest,
           OLD.signature_digest, OLD.mandatory_bound_digest, OLD.signature_verified,
           OLD.review_status, OLD.source_reference, OLD.created_at, OLD.created_by
       ) IS DISTINCT FROM ROW(
           NEW.tenant_id, NEW.public_id, NEW.pack_key, NEW.jurisdiction_country,
           NEW.jurisdiction_subdivision, NEW.policy_revision, NEW.effective_from,
           NEW.effective_to, NEW.schema_version, NEW.schema_digest,
           NEW.signature_digest, NEW.mandatory_bound_digest, NEW.signature_verified,
           NEW.review_status, NEW.source_reference, NEW.created_at, NEW.created_by
       ) THEN
        RAISE EXCEPTION 'validated rule-pack content is immutable';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_tim_guard_rule_pack_revision BEFORE INSERT OR UPDATE OR DELETE ON tim_rule_pack_versions
    FOR EACH ROW EXECUTE FUNCTION tim_guard_rule_pack_revision();
CREATE OR REPLACE FUNCTION tim_guard_rule_pack_parameter()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    parent_state VARCHAR(20);
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        SELECT lifecycle_state INTO parent_state
          FROM tim_rule_pack_versions
         WHERE tenant_id = OLD.tenant_id
           AND rule_pack_version_id = OLD.rule_pack_version_id
         FOR SHARE;
        IF parent_state IS DISTINCT FROM 'STAGED' THEN
            RAISE EXCEPTION 'rule-pack parameters are mutable only while STAGED';
        END IF;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        SELECT lifecycle_state INTO parent_state
          FROM tim_rule_pack_versions
         WHERE tenant_id = NEW.tenant_id
           AND rule_pack_version_id = NEW.rule_pack_version_id
         FOR SHARE;
        IF parent_state IS DISTINCT FROM 'STAGED' THEN
            RAISE EXCEPTION 'rule-pack parameters are mutable only while STAGED';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$function$;
CREATE TRIGGER trg_tim_guard_rule_pack_parameter BEFORE INSERT OR UPDATE OR DELETE ON tim_rule_pack_parameters
    FOR EACH ROW EXECUTE FUNCTION tim_guard_rule_pack_parameter();
CREATE OR REPLACE FUNCTION tim_guard_work_plan_assignment()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'DRAFT' OR NEW.version <> 1 THEN
            RAISE EXCEPTION 'work-plan assignments must start as DRAFT at version one';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.lifecycle_state <> 'DRAFT' THEN
            RAISE EXCEPTION 'published work-plan assignments are immutable';
        END IF;
        RETURN OLD;
    END IF;
    IF NOT (
        NEW.lifecycle_state = OLD.lifecycle_state
        OR (OLD.lifecycle_state = 'DRAFT' AND NEW.lifecycle_state IN ('PUBLISHED', 'CANCELLED'))
        OR (OLD.lifecycle_state = 'PUBLISHED' AND NEW.lifecycle_state IN ('SUPERSEDED', 'CANCELLED'))
    ) THEN
        RAISE EXCEPTION 'invalid work-plan assignment transition: % -> %',
            OLD.lifecycle_state, NEW.lifecycle_state;
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'work-plan assignment version must advance exactly once';
    END IF;
    IF (OLD.lifecycle_state <> 'DRAFT' OR NEW.lifecycle_state <> OLD.lifecycle_state)
       AND ROW(
           OLD.tenant_id, OLD.public_id, OLD.worker_public_id,
           OLD.people_assignment_public_id, OLD.people_assignment_revision,
           OLD.work_regime_version_id, OLD.rule_pack_public_id,
           OLD.effective_from, OLD.effective_to,
           OLD.zone_id, OLD.jurisdiction_country, OLD.jurisdiction_subdivision,
           OLD.policy_revision, OLD.source_context_digest, OLD.created_at, OLD.created_by
       ) IS DISTINCT FROM ROW(
           NEW.tenant_id, NEW.public_id, NEW.worker_public_id,
           NEW.people_assignment_public_id, NEW.people_assignment_revision,
           NEW.work_regime_version_id, NEW.rule_pack_public_id,
           NEW.effective_from, NEW.effective_to,
           NEW.zone_id, NEW.jurisdiction_country, NEW.jurisdiction_subdivision,
           NEW.policy_revision, NEW.source_context_digest, NEW.created_at, NEW.created_by
       ) THEN
        RAISE EXCEPTION 'published work-plan assignment content is immutable';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_tim_guard_work_plan_assignment BEFORE INSERT OR UPDATE OR DELETE ON tim_work_plan_assignments
    FOR EACH ROW EXECUTE FUNCTION tim_guard_work_plan_assignment();
CREATE OR REPLACE FUNCTION tim_guard_simulation_run()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'ACCEPTED'
           OR NEW.result_digest IS NOT NULL
           OR NEW.calculated_at IS NOT NULL
           OR NEW.findings <> '[]'::jsonb
           OR NEW.finding_count <> 0
           OR NEW.partial_failure_count <> 0 THEN
            RAISE EXCEPTION 'simulation runs must start as an empty ACCEPTED receipt';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(
        OLD.tenant_id, OLD.public_id, OLD.work_regime_version_id,
        OLD.work_plan_assignment_id, OLD.simulation_from, OLD.simulation_to,
        OLD.command_receipt_public_id, OLD.idempotency_key, OLD.request_digest,
        OLD.zone_id, OLD.tzdb_version,
        OLD.rule_pack_public_id, OLD.policy_revision, OLD.resolution_digest,
        OLD.created_at, OLD.created_by
    ) IS DISTINCT FROM ROW(
        NEW.tenant_id, NEW.public_id, NEW.work_regime_version_id,
        NEW.work_plan_assignment_id, NEW.simulation_from, NEW.simulation_to,
        NEW.command_receipt_public_id, NEW.idempotency_key, NEW.request_digest,
        NEW.zone_id, NEW.tzdb_version,
        NEW.rule_pack_public_id, NEW.policy_revision, NEW.resolution_digest,
        NEW.created_at, NEW.created_by
    ) THEN
        RAISE EXCEPTION 'simulation identity and input evidence are immutable';
    END IF;
    IF OLD.lifecycle_state IN ('SUCCEEDED', 'REJECTED', 'FAILED') THEN
        RAISE EXCEPTION 'terminal simulation runs are immutable';
    END IF;
    IF OLD.lifecycle_state = 'ACCEPTED'
       AND NEW.lifecycle_state NOT IN ('ACCEPTED', 'RUNNING') THEN
        RAISE EXCEPTION 'invalid accepted simulation transition';
    END IF;
    IF OLD.lifecycle_state IN ('RUNNING', 'RESULT_UNKNOWN')
       AND NEW.lifecycle_state NOT IN (
           'RUNNING', 'SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN'
       ) THEN
        RAISE EXCEPTION 'invalid in-flight simulation transition';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_tim_guard_simulation_run BEFORE INSERT OR UPDATE ON tim_schedule_simulation_runs
    FOR EACH ROW EXECUTE FUNCTION tim_guard_simulation_run();
CREATE OR REPLACE FUNCTION tim_guard_simulation_segment()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    parent_state VARCHAR(20);
    parent_assignment_id BIGINT;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        SELECT lifecycle_state, work_plan_assignment_id
          INTO parent_state, parent_assignment_id
          FROM tim_schedule_simulation_runs
         WHERE tenant_id = OLD.tenant_id
           AND schedule_simulation_run_id = OLD.schedule_simulation_run_id
         FOR KEY SHARE;
        IF parent_state IS NULL OR parent_state NOT IN ('ACCEPTED', 'RUNNING') THEN
            RAISE EXCEPTION 'terminal simulation segments are immutable';
        END IF;
        IF parent_assignment_id IS NOT NULL
           AND parent_assignment_id <> OLD.work_plan_assignment_id THEN
            RAISE EXCEPTION 'simulation segment assignment does not match its run';
        END IF;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        SELECT lifecycle_state, work_plan_assignment_id
          INTO parent_state, parent_assignment_id
          FROM tim_schedule_simulation_runs
         WHERE tenant_id = NEW.tenant_id
           AND schedule_simulation_run_id = NEW.schedule_simulation_run_id
         FOR KEY SHARE;
        IF parent_state IS NULL OR parent_state NOT IN ('ACCEPTED', 'RUNNING') THEN
            RAISE EXCEPTION 'terminal simulation segments are immutable';
        END IF;
        IF parent_assignment_id IS NOT NULL
           AND parent_assignment_id <> NEW.work_plan_assignment_id THEN
            RAISE EXCEPTION 'simulation segment assignment does not match its run';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$function$;
CREATE TRIGGER trg_tim_guard_simulation_segment BEFORE INSERT OR UPDATE OR DELETE ON tim_schedule_simulation_segments
    FOR EACH ROW EXECUTE FUNCTION tim_guard_simulation_segment();
CREATE OR REPLACE FUNCTION tim_guard_command_receipt_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'ACCEPTED'
           OR NEW.result_code IS NOT NULL
           OR NEW.result_digest IS NOT NULL THEN
            RAISE EXCEPTION 'command receipts must start as an empty ACCEPTED receipt';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(OLD.tenant_id, OLD.public_id, OLD.idempotency_key,
           OLD.operation, OLD.aggregate_public_id, OLD.scope_public_ref,
           OLD.expected_version, OLD.request_digest, OLD.actor_id, OLD.purpose_code,
           OLD.authorization_decision_id, OLD.correlation_id, OLD.created_at)
       IS DISTINCT FROM
       ROW(NEW.tenant_id, NEW.public_id, NEW.idempotency_key,
           NEW.operation, NEW.aggregate_public_id, NEW.scope_public_ref,
           NEW.expected_version, NEW.request_digest, NEW.actor_id, NEW.purpose_code,
           NEW.authorization_decision_id, NEW.correlation_id, NEW.created_at) THEN
        RAISE EXCEPTION 'command receipt identity and request evidence are immutable';
    END IF;
    IF OLD.lifecycle_state IN ('SUCCEEDED', 'REJECTED', 'FAILED') THEN
        RAISE EXCEPTION 'terminal command receipts are immutable';
    END IF;
    IF OLD.lifecycle_state = 'ACCEPTED'
       AND NEW.lifecycle_state NOT IN ('ACCEPTED', 'RUNNING', 'RESULT_UNKNOWN') THEN
        RAISE EXCEPTION 'invalid accepted receipt transition';
    END IF;
    IF OLD.lifecycle_state = 'RUNNING'
       AND NEW.lifecycle_state NOT IN (
           'RUNNING', 'SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN'
       ) THEN
        RAISE EXCEPTION 'invalid in-flight receipt transition';
    END IF;
    IF OLD.lifecycle_state = 'RESULT_UNKNOWN'
       AND NEW.lifecycle_state NOT IN ('RESULT_UNKNOWN', 'RECONCILING') THEN
        RAISE EXCEPTION 'result-unknown receipts must enter reconciliation';
    END IF;
    IF OLD.lifecycle_state = 'RECONCILING'
       AND NEW.lifecycle_state NOT IN (
           'RECONCILING', 'SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN'
       ) THEN
        RAISE EXCEPTION 'invalid reconciliation receipt transition';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_tim_guard_command_receipt_transition BEFORE INSERT OR UPDATE ON tim_command_receipts
    FOR EACH ROW EXECUTE FUNCTION tim_guard_command_receipt_transition();
CREATE OR REPLACE FUNCTION tim_reject_immutable_row_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION 'immutable TIM evidence cannot be updated or deleted';
END;
$function$;
CREATE TRIGGER trg_tim_work_regime_audit_immutable BEFORE UPDATE OR DELETE ON tim_work_regime_audit_events
    FOR EACH ROW EXECUTE FUNCTION tim_reject_immutable_row_mutation();
CREATE OR REPLACE FUNCTION tim_guard_outbox_delivery_update()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'outbox evidence cannot be deleted';
    END IF;
    IF ROW(
        OLD.tenant_id, OLD.public_id, OLD.aggregate_type, OLD.aggregate_public_id,
        OLD.aggregate_revision, OLD.command_receipt_public_id, OLD.event_type,
        OLD.event_payload, OLD.payload_digest, OLD.correlation_id, OLD.occurred_at
    ) IS DISTINCT FROM ROW(
        NEW.tenant_id, NEW.public_id, NEW.aggregate_type, NEW.aggregate_public_id,
        NEW.aggregate_revision, NEW.command_receipt_public_id, NEW.event_type,
        NEW.event_payload, NEW.payload_digest, NEW.correlation_id, NEW.occurred_at
    ) THEN
        RAISE EXCEPTION 'outbox event identity and payload are immutable';
    END IF;
    IF NEW.publish_attempts < OLD.publish_attempts
       OR NEW.publish_attempts > OLD.publish_attempts + 1 THEN
        RAISE EXCEPTION 'outbox publish attempts must advance monotonically';
    END IF;
    IF OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at THEN
        RAISE EXCEPTION 'published outbox evidence is immutable';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_tim_guard_outbox_delivery_update BEFORE UPDATE OR DELETE ON tim_work_regime_outbox_events
    FOR EACH ROW EXECUTE FUNCTION tim_guard_outbox_delivery_update();
DO $block$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'tim_work_regime_versions',
        'tim_work_regime_policy_terms',
        'tim_work_regime_segments',
        'tim_rule_pack_versions',
        'tim_rule_pack_parameters',
        'tim_work_plan_assignments',
        'tim_schedule_simulation_runs',
        'tim_schedule_simulation_segments',
        'tim_command_receipts',
        'tim_work_regime_audit_events',
        'tim_work_regime_outbox_events'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING '
            || '(tenant_id = NULLIF(current_setting(''dwp.tenant_id'', true), '''')::BIGINT) '
            || 'WITH CHECK '
            || '(tenant_id = NULLIF(current_setting(''dwp.tenant_id'', true), '''')::BIGINT)',
            table_name || '_tenant_isolation', table_name
        );
    END LOOP;
END;
$block$;
REVOKE ALL ON TABLE
    tim_work_regime_versions,
    tim_work_regime_policy_terms,
    tim_work_regime_segments,
    tim_rule_pack_versions,
    tim_rule_pack_parameters,
    tim_work_plan_assignments,
    tim_schedule_simulation_runs,
    tim_schedule_simulation_segments,
    tim_command_receipts,
    tim_work_regime_audit_events,
    tim_work_regime_outbox_events
FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE
    tim_work_regime_versions,
    tim_work_plan_assignments,
    tim_schedule_simulation_runs,
    tim_command_receipts,
    tim_work_regime_outbox_events
TO "${timeRuntimeRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    tim_work_regime_policy_terms,
    tim_work_regime_segments,
    tim_schedule_simulation_segments
TO "${timeRuntimeRole}";
GRANT SELECT ON TABLE tim_rule_pack_versions, tim_rule_pack_parameters
TO "${timeRuntimeRole}";
GRANT SELECT, INSERT ON TABLE tim_work_regime_audit_events
TO "${timeRuntimeRole}";
GRANT USAGE, SELECT ON SEQUENCE
    tim_work_regime_versions_work_regime_version_id_seq,
    tim_work_regime_policy_terms_work_regime_policy_term_id_seq,
    tim_work_regime_segments_work_regime_segment_id_seq,
    tim_work_plan_assignments_work_plan_assignment_id_seq,
    tim_schedule_simulation_runs_schedule_simulation_run_id_seq,
    tim_schedule_simulation_segment_id_seq,
    tim_command_receipts_command_receipt_id_seq,
    tim_work_regime_audit_events_audit_event_id_seq,
    tim_work_regime_outbox_events_outbox_event_id_seq
TO "${timeRuntimeRole}";
REVOKE ALL ON FUNCTION
    tim_guard_published_regime_revision(),
    tim_guard_regime_child_mutation(),
    tim_guard_rule_pack_revision(),
    tim_guard_rule_pack_parameter(),
    tim_guard_work_plan_assignment(),
    tim_guard_simulation_run(),
    tim_guard_simulation_segment(),
    tim_guard_command_receipt_transition(),
    tim_reject_immutable_row_mutation(),
    tim_guard_outbox_delivery_update()
FROM PUBLIC;
