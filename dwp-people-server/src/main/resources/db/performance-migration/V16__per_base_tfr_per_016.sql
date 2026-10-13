-- BASE-TFR-PER-016 / W2-20261013-PER-016-START-R1
-- PER-owned, revision-bound projection of the sealed HRM workforce snapshot contract.
-- HRM public identifiers are evidence only: this migration creates no cross-context
-- foreign keys and grants no write path into the HRM/People bounded context.

CREATE TABLE hris_performance.prf_workforce_projection_heads (
    projection_head_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    snapshot_as_of TIMESTAMPTZ,
    purpose_code VARCHAR(80) NOT NULL,
    projection_code VARCHAR(40) NOT NULL,
    projection_state VARCHAR(40) NOT NULL,
    current_local_revision BIGINT NOT NULL DEFAULT 0,
    current_owner_revision BIGINT,
    current_content_hash CHAR(64),
    stale_event_id UUID,
    blocked_expected_revision BIGINT,
    blocked_observed_revision BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (projection_head_id),
    CONSTRAINT uk_prf_workforce_projection_heads_tenant_id UNIQUE (
        tenant_id, projection_head_id
    ),
    CONSTRAINT uk_prf_workforce_projection_heads_scope UNIQUE (tenant_id),
    CONSTRAINT ck_prf_workforce_projection_heads_contract CHECK (
        purpose_code = 'HRIS_WORKFORCE_SNAPSHOT_BOOTSTRAP'
        AND projection_code = 'PERFORMANCE_V1'
    ),
    CONSTRAINT ck_prf_workforce_projection_heads_state CHECK (
        projection_state IN (
            'READY',
            'STALE',
            'BLOCKED_DEPENDENCY_GAP',
            'QUARANTINED'
        )
    ),
    CONSTRAINT ck_prf_workforce_projection_heads_revisions CHECK (
        current_local_revision >= 0
        AND (current_owner_revision IS NULL OR current_owner_revision > 0)
        AND (
            blocked_expected_revision IS NULL
            OR blocked_expected_revision > 0
        )
        AND (
            blocked_observed_revision IS NULL
            OR blocked_observed_revision > 0
        )
    ),
    CONSTRAINT ck_prf_workforce_projection_heads_hash CHECK (
        current_content_hash IS NULL
        OR current_content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_prf_workforce_projection_heads_current CHECK (
        (
            current_local_revision = 0
            AND current_owner_revision IS NULL
            AND current_content_hash IS NULL
            AND snapshot_as_of IS NULL
        )
        OR (
            current_local_revision > 0
            AND current_owner_revision IS NOT NULL
            AND current_content_hash IS NOT NULL
            AND snapshot_as_of IS NOT NULL
        )
    ),
    CONSTRAINT ck_prf_workforce_projection_heads_ready CHECK (
        projection_state <> 'READY' OR current_local_revision > 0
    ),
    CONSTRAINT ck_prf_workforce_projection_heads_gap CHECK (
        (
            projection_state = 'BLOCKED_DEPENDENCY_GAP'
            AND blocked_expected_revision IS NOT NULL
            AND blocked_observed_revision IS NOT NULL
            AND blocked_observed_revision > blocked_expected_revision
        )
        OR (
            projection_state <> 'BLOCKED_DEPENDENCY_GAP'
            AND blocked_expected_revision IS NULL
            AND blocked_observed_revision IS NULL
        )
    )
);

CREATE TABLE hris_performance.prf_workforce_projection_revisions (
    projection_revision_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    projection_head_id UUID NOT NULL,
    local_revision BIGINT NOT NULL,
    owner_revision BIGINT NOT NULL,
    snapshot_as_of TIMESTAMPTZ NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    projection_code VARCHAR(40) NOT NULL,
    owner_page_digest CHAR(64) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    member_count INTEGER NOT NULL,
    replay_of_local_revision BIGINT,
    materialized_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (projection_revision_id),
    CONSTRAINT uk_prf_workforce_projection_revisions_tenant_id UNIQUE (
        tenant_id, projection_revision_id
    ),
    CONSTRAINT uk_prf_workforce_projection_revisions_sequence UNIQUE (
        tenant_id, projection_head_id, local_revision
    ),
    CONSTRAINT fk_prf_workforce_projection_revisions_head FOREIGN KEY (
        tenant_id, projection_head_id
    ) REFERENCES hris_performance.prf_workforce_projection_heads (
        tenant_id, projection_head_id
    ),
    CONSTRAINT ck_prf_workforce_projection_revisions_values CHECK (
        local_revision > 0
        AND owner_revision > 0
        AND member_count >= 0
        AND (
            replay_of_local_revision IS NULL
            OR (
                replay_of_local_revision > 0
                AND replay_of_local_revision < local_revision
            )
        )
    ),
    CONSTRAINT ck_prf_workforce_projection_revisions_contract CHECK (
        purpose_code = 'HRIS_WORKFORCE_SNAPSHOT_BOOTSTRAP'
        AND projection_code = 'PERFORMANCE_V1'
    ),
    CONSTRAINT ck_prf_workforce_projection_revisions_hashes CHECK (
        owner_page_digest ~ '^[0-9a-f]{64}$'
        AND content_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE hris_performance.prf_workforce_projection_members (
    projection_member_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    projection_head_id UUID NOT NULL,
    local_revision BIGINT NOT NULL,
    worker_public_id UUID NOT NULL,
    assignment_public_id UUID NOT NULL,
    workforce_status VARCHAR(24) NOT NULL,
    organization_public_id UUID NOT NULL,
    manager_assignment_public_id UUID,
    job_profile_public_id UUID,
    grade_public_id UUID,
    PRIMARY KEY (projection_member_id),
    CONSTRAINT uk_prf_workforce_projection_members_tenant_id UNIQUE (
        tenant_id, projection_member_id
    ),
    CONSTRAINT uk_prf_workforce_projection_members_assignment UNIQUE (
        tenant_id, projection_head_id, local_revision, assignment_public_id
    ),
    CONSTRAINT uk_prf_workforce_projection_members_pair UNIQUE (
        tenant_id, projection_head_id, local_revision,
        worker_public_id, assignment_public_id
    ),
    CONSTRAINT fk_prf_workforce_projection_members_revision FOREIGN KEY (
        tenant_id, projection_head_id, local_revision
    ) REFERENCES hris_performance.prf_workforce_projection_revisions (
        tenant_id, projection_head_id, local_revision
    ),
    CONSTRAINT ck_prf_workforce_projection_members_status CHECK (
        workforce_status IN ('PENDING', 'ACTIVE', 'INACTIVE', 'TERMINATED')
    )
);

CREATE TABLE hris_performance.prf_workforce_projection_inbox (
    inbox_receipt_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_public_id UUID NOT NULL,
    aggregate_revision BIGINT,
    organization_revision_public_id UUID,
    effective_date DATE NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    conflicting_payload_hash CHAR(64),
    processing_state VARCHAR(24) NOT NULL,
    outcome_code VARCHAR(80) NOT NULL,
    resulting_projection_state VARCHAR(40) NOT NULL,
    first_received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,
    conflict_detected_at TIMESTAMPTZ,
    PRIMARY KEY (inbox_receipt_id),
    CONSTRAINT uk_prf_workforce_projection_inbox_tenant_id UNIQUE (
        tenant_id, inbox_receipt_id
    ),
    CONSTRAINT uk_prf_workforce_projection_inbox_event UNIQUE (
        tenant_id, event_id
    ),
    CONSTRAINT ck_prf_workforce_projection_inbox_event_type CHECK (
        event_type IN (
            'WorkerChanged.v1',
            'EmploymentChanged.v1',
            'AssignmentChanged.v1',
            'OrganizationChanged.v1'
        )
    ),
    CONSTRAINT ck_prf_workforce_projection_inbox_aggregate_type CHECK (
        aggregate_type IN ('WORKER', 'EMPLOYMENT', 'ASSIGNMENT', 'ORGANIZATION')
    ),
    CONSTRAINT ck_prf_workforce_projection_inbox_event_binding CHECK (
        (event_type = 'WorkerChanged.v1' AND aggregate_type = 'WORKER')
        OR (
            event_type = 'EmploymentChanged.v1'
            AND aggregate_type = 'EMPLOYMENT'
        )
        OR (
            event_type = 'AssignmentChanged.v1'
            AND aggregate_type = 'ASSIGNMENT'
        )
        OR (
            event_type = 'OrganizationChanged.v1'
            AND aggregate_type = 'ORGANIZATION'
        )
    ),
    CONSTRAINT ck_prf_workforce_projection_inbox_revision_binding CHECK (
        (
            event_type <> 'OrganizationChanged.v1'
            AND aggregate_revision IS NOT NULL
            AND aggregate_revision > 0
            AND organization_revision_public_id IS NULL
        )
        OR (
            event_type = 'OrganizationChanged.v1'
            AND aggregate_revision IS NULL
            AND organization_revision_public_id IS NOT NULL
        )
    ),
    CONSTRAINT ck_prf_workforce_projection_inbox_hashes CHECK (
        payload_hash ~ '^[0-9a-f]{64}$'
        AND (
            conflicting_payload_hash IS NULL
            OR (
                conflicting_payload_hash ~ '^[0-9a-f]{64}$'
                AND conflicting_payload_hash <> payload_hash
            )
        )
    ),
    CONSTRAINT ck_prf_workforce_projection_inbox_processing_state CHECK (
        processing_state IN (
            'RECEIVED', 'PROCESSED', 'IGNORED', 'REPLAY_REQUIRED', 'QUARANTINED'
        )
    ),
    CONSTRAINT ck_prf_workforce_projection_inbox_projection_state CHECK (
        resulting_projection_state IN (
            'READY',
            'STALE',
            'BLOCKED_DEPENDENCY_GAP',
            'QUARANTINED'
        )
    ),
    CONSTRAINT ck_prf_workforce_projection_inbox_outcome CHECK (
        outcome_code IN (
            'STALE',
            'BLOCKED_DEPENDENCY_GAP',
            'IGNORED_OLDER_REVISION',
            'REPLAYED',
            'QUARANTINED'
        )
    ),
    CONSTRAINT ck_prf_workforce_projection_inbox_conflict CHECK (
        (
            processing_state = 'QUARANTINED'
            AND conflicting_payload_hash IS NOT NULL
            AND conflict_detected_at IS NOT NULL
        )
        OR (
            processing_state <> 'QUARANTINED'
            AND conflicting_payload_hash IS NULL
            AND conflict_detected_at IS NULL
        )
    )
);

CREATE INDEX ix_prf_workforce_projection_heads_state
    ON hris_performance.prf_workforce_projection_heads (
        tenant_id, projection_state, updated_at DESC
    );

CREATE INDEX ix_prf_workforce_projection_revisions_owner
    ON hris_performance.prf_workforce_projection_revisions (
        tenant_id, projection_head_id, owner_revision, local_revision DESC
    );

CREATE INDEX ix_prf_workforce_projection_members_worker
    ON hris_performance.prf_workforce_projection_members (
        tenant_id, worker_public_id, local_revision DESC
    );

CREATE INDEX ix_prf_workforce_projection_inbox_watermark
    ON hris_performance.prf_workforce_projection_inbox (
        tenant_id, aggregate_type, aggregate_public_id, aggregate_revision DESC
    )
    WHERE aggregate_revision IS NOT NULL;

CREATE INDEX ix_prf_workforce_projection_inbox_recovery
    ON hris_performance.prf_workforce_projection_inbox (
        tenant_id, processing_state, first_received_at
    )
    WHERE processing_state IN ('REPLAY_REQUIRED', 'QUARANTINED');

ALTER TABLE hris_performance.prf_workforce_projection_heads
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_performance.prf_workforce_projection_revisions
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_performance.prf_workforce_projection_members
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_performance.prf_workforce_projection_inbox
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE hris_performance.prf_workforce_projection_heads
    FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_performance.prf_workforce_projection_revisions
    FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_performance.prf_workforce_projection_members
    FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_performance.prf_workforce_projection_inbox
    FORCE ROW LEVEL SECURITY;

CREATE POLICY prf_workforce_projection_heads_tenant_isolation
    ON hris_performance.prf_workforce_projection_heads
    USING (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
    );

CREATE POLICY prf_workforce_projection_revisions_tenant_isolation
    ON hris_performance.prf_workforce_projection_revisions
    USING (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
    );

CREATE POLICY prf_workforce_projection_members_tenant_isolation
    ON hris_performance.prf_workforce_projection_members
    USING (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
    );

CREATE POLICY prf_workforce_projection_inbox_tenant_isolation
    ON hris_performance.prf_workforce_projection_inbox
    USING (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
    );
