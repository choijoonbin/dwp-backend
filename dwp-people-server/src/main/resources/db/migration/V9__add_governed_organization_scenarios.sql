CREATE TABLE ppl_organization_change_type_catalog (
    change_type VARCHAR(80) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    target_kind VARCHAR(24) NOT NULL,
    risk_tier VARCHAR(10) NOT NULL,
    requires_approval BOOLEAN NOT NULL DEFAULT TRUE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    payload_schema_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ppl_org_change_type_key
        CHECK (change_type = UPPER(BTRIM(change_type))
            AND change_type ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_ppl_org_change_target_kind
        CHECK (target_kind IN ('ORGANIZATION', 'ASSIGNMENT', 'POSITION')),
    CONSTRAINT ck_ppl_org_change_risk CHECK (risk_tier IN ('L1', 'L2', 'L3')),
    CONSTRAINT ck_ppl_org_change_type_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_ppl_org_change_schema_version CHECK (payload_schema_version > 0)
);

INSERT INTO ppl_organization_change_type_catalog (
    change_type, display_name, target_kind, risk_tier, requires_approval)
VALUES
    ('MOVE_ORGANIZATION', 'Move organization', 'ORGANIZATION', 'L3', TRUE),
    ('RENAME_ORGANIZATION', 'Rename organization', 'ORGANIZATION', 'L2', TRUE),
    ('CHANGE_ORGANIZATION_LEADER', 'Change organization leader', 'ORGANIZATION', 'L2', TRUE),
    ('MOVE_ASSIGNMENT', 'Move worker assignment', 'ASSIGNMENT', 'L2', TRUE),
    ('CHANGE_MANAGER', 'Change manager', 'ASSIGNMENT', 'L2', TRUE),
    ('CREATE_POSITION', 'Create position', 'POSITION', 'L2', TRUE),
    ('CLOSE_POSITION', 'Close position', 'POSITION', 'L2', TRUE)
ON CONFLICT (change_type) DO NOTHING;

CREATE TABLE ppl_organization_scenarios (
    organization_scenario_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    scenario_key VARCHAR(100) NOT NULL,
    name VARCHAR(240) NOT NULL,
    description VARCHAR(2000),
    baseline_date DATE NOT NULL,
    effective_date DATE NOT NULL,
    baseline_fingerprint CHAR(64) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    owner_user_id BIGINT NOT NULL,
    submitted_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    published_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_org_scenarios_key UNIQUE (tenant_id, scenario_key),
    CONSTRAINT uk_ppl_org_scenarios_id UNIQUE (tenant_id, organization_scenario_id),
    CONSTRAINT ck_ppl_org_scenarios_key
        CHECK (scenario_key = LOWER(BTRIM(scenario_key))
            AND scenario_key ~ '^[a-z][a-z0-9-]{2,99}$'),
    CONSTRAINT ck_ppl_org_scenarios_dates CHECK (effective_date >= baseline_date),
    CONSTRAINT ck_ppl_org_scenarios_fingerprint
        CHECK (baseline_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ppl_org_scenarios_state
        CHECK (lifecycle_state IN (
            'DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED',
            'PUBLISHED', 'CANCELLED', 'STALE')),
    CONSTRAINT ck_ppl_org_scenarios_submit
        CHECK (lifecycle_state NOT IN ('IN_REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED', 'STALE')
            OR submitted_at IS NOT NULL),
    CONSTRAINT ck_ppl_org_scenarios_publish
        CHECK ((lifecycle_state = 'PUBLISHED')
            = (published_at IS NOT NULL AND published_by IS NOT NULL))
);

CREATE TABLE ppl_organization_scenario_changes (
    organization_scenario_change_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    organization_scenario_id UUID NOT NULL,
    change_sequence INTEGER NOT NULL,
    change_type VARCHAR(80) NOT NULL
        REFERENCES ppl_organization_change_type_catalog(change_type),
    target_kind VARCHAR(24) NOT NULL,
    target_reference VARCHAR(255) NOT NULL,
    related_reference VARCHAR(255),
    effective_date DATE NOT NULL,
    before_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    after_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    estimated_headcount_delta INTEGER NOT NULL DEFAULT 0,
    estimated_fte_delta NUMERIC(10, 4) NOT NULL DEFAULT 0,
    estimated_cost_delta NUMERIC(18, 2),
    cost_currency CHAR(3),
    validation_state VARCHAR(20) NOT NULL DEFAULT 'VALID',
    validation_message VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT fk_ppl_org_scenario_changes_scenario
        FOREIGN KEY (tenant_id, organization_scenario_id)
        REFERENCES ppl_organization_scenarios(tenant_id, organization_scenario_id),
    CONSTRAINT uk_ppl_org_scenario_change_sequence
        UNIQUE (organization_scenario_id, change_sequence),
    CONSTRAINT ck_ppl_org_scenario_changes_sequence CHECK (change_sequence > 0),
    CONSTRAINT ck_ppl_org_scenario_changes_target_kind
        CHECK (target_kind IN ('ORGANIZATION', 'ASSIGNMENT', 'POSITION')),
    CONSTRAINT ck_ppl_org_scenario_changes_snapshots
        CHECK (jsonb_typeof(before_snapshot) = 'object'
            AND jsonb_typeof(after_snapshot) = 'object'),
    CONSTRAINT ck_ppl_org_scenario_changes_fte
        CHECK (estimated_fte_delta BETWEEN -1000000 AND 1000000),
    CONSTRAINT ck_ppl_org_scenario_changes_cost
        CHECK ((estimated_cost_delta IS NULL) = (cost_currency IS NULL)),
    CONSTRAINT ck_ppl_org_scenario_changes_validation
        CHECK (validation_state IN ('VALID', 'WARNING', 'BLOCKED'))
);

CREATE TABLE ppl_organization_scenario_approvals (
    organization_scenario_approval_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    organization_scenario_id UUID NOT NULL,
    gate_key VARCHAR(80) NOT NULL,
    gate_order INTEGER NOT NULL DEFAULT 1,
    required_role_code VARCHAR(80) NOT NULL,
    separation_of_duties BOOLEAN NOT NULL DEFAULT TRUE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_by BIGINT NOT NULL,
    decided_by BIGINT,
    request_reason VARCHAR(1000) NOT NULL,
    decision_reason VARCHAR(1000),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '7 days'),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_ppl_org_scenario_approvals_scenario
        FOREIGN KEY (tenant_id, organization_scenario_id)
        REFERENCES ppl_organization_scenarios(tenant_id, organization_scenario_id),
    CONSTRAINT uk_ppl_org_scenario_approval_gate
        UNIQUE (organization_scenario_id, gate_key),
    CONSTRAINT ck_ppl_org_scenario_approval_key
        CHECK (gate_key = UPPER(BTRIM(gate_key))
            AND gate_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_ppl_org_scenario_approval_order CHECK (gate_order > 0),
    CONSTRAINT ck_ppl_org_scenario_approval_state
        CHECK (lifecycle_state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_ppl_org_scenario_approval_window CHECK (expires_at > requested_at),
    CONSTRAINT ck_ppl_org_scenario_approval_separation
        CHECK (decided_by IS NULL OR separation_of_duties = FALSE OR decided_by <> requested_by),
    CONSTRAINT ck_ppl_org_scenario_approval_decision
        CHECK (
            (lifecycle_state IN ('APPROVED', 'REJECTED')
                AND decided_by IS NOT NULL AND decided_at IS NOT NULL
                AND decision_reason IS NOT NULL)
            OR (lifecycle_state NOT IN ('APPROVED', 'REJECTED')
                AND decided_by IS NULL AND decided_at IS NULL)
        )
);

CREATE INDEX idx_ppl_org_scenarios_queue
    ON ppl_organization_scenarios(tenant_id, lifecycle_state, effective_date, updated_at DESC);
CREATE INDEX idx_ppl_org_scenario_changes_target
    ON ppl_organization_scenario_changes(
        tenant_id, target_kind, target_reference, effective_date, organization_scenario_id);
CREATE INDEX idx_ppl_org_scenario_approvals_queue
    ON ppl_organization_scenario_approvals(
        tenant_id, lifecycle_state, expires_at, requested_at);

CREATE INDEX idx_ppl_organizations_search
    ON ppl_organizations USING GIN (
        to_tsvector('simple',
            COALESCE(name, '') || ' ' || COALESCE(short_name, '') || ' '
            || COALESCE(organization_key, '') || ' ' || COALESCE(cost_center_key, '')));
CREATE INDEX idx_ppl_persons_search
    ON ppl_persons USING GIN (
        to_tsvector('simple', COALESCE(display_name, '') || ' ' || COALESCE(person_key, '')));
CREATE INDEX idx_ppl_job_profiles_search
    ON ppl_job_profiles USING GIN (
        to_tsvector('simple', COALESCE(name, '') || ' ' || COALESCE(job_key, '')));
