CREATE TABLE com_access_review_campaigns (
    access_review_campaign_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    scope_type VARCHAR(20) NOT NULL DEFAULT 'TENANT',
    scope_ref BIGINT,
    reviewer_strategy VARCHAR(30) NOT NULL DEFAULT 'TENANT_ADMIN',
    reviewer_user_id BIGINT,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    due_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_access_review_campaign_reviewer
        FOREIGN KEY (tenant_id, reviewer_user_id)
        REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT ck_access_review_campaign_scope
        CHECK (scope_type IN ('TENANT', 'ROLE', 'GROUP')),
    CONSTRAINT ck_access_review_campaign_scope_ref
        CHECK ((scope_type = 'TENANT' AND scope_ref IS NULL)
            OR (scope_type <> 'TENANT' AND scope_ref IS NOT NULL)),
    CONSTRAINT ck_access_review_campaign_reviewer_strategy
        CHECK (reviewer_strategy IN ('TENANT_ADMIN', 'NAMED_REVIEWER')),
    CONSTRAINT ck_access_review_campaign_reviewer
        CHECK ((reviewer_strategy = 'TENANT_ADMIN' AND reviewer_user_id IS NULL)
            OR (reviewer_strategy = 'NAMED_REVIEWER' AND reviewer_user_id IS NOT NULL)),
    CONSTRAINT ck_access_review_campaign_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'CANCELLED'))
);

CREATE TABLE com_access_review_items (
    access_review_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    access_review_campaign_id UUID NOT NULL
        REFERENCES com_access_review_campaigns(access_review_campaign_id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    subject_user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    access_source_type VARCHAR(20) NOT NULL,
    access_source_id BIGINT NOT NULL,
    reviewer_user_id BIGINT,
    decision VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    decision_reason VARCHAR(1000),
    decided_by BIGINT,
    decided_at TIMESTAMPTZ,
    remediation_state VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_access_review_item_subject
        FOREIGN KEY (tenant_id, subject_user_id)
        REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT fk_access_review_item_role
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES com_roles(tenant_id, role_id),
    CONSTRAINT fk_access_review_item_reviewer
        FOREIGN KEY (tenant_id, reviewer_user_id)
        REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT uk_access_review_item_source UNIQUE (
        access_review_campaign_id, subject_user_id, role_id,
        access_source_type, access_source_id),
    CONSTRAINT ck_access_review_item_source
        CHECK (access_source_type IN ('DIRECT', 'GROUP')),
    CONSTRAINT ck_access_review_item_decision
        CHECK (decision IN ('PENDING', 'APPROVE', 'REVOKE')),
    CONSTRAINT ck_access_review_item_remediation
        CHECK (remediation_state IN (
            'NOT_REQUIRED', 'PENDING', 'APPLIED', 'MANUAL_REQUIRED', 'FAILED')),
    CONSTRAINT ck_access_review_item_decided
        CHECK ((decision = 'PENDING' AND decided_at IS NULL AND decided_by IS NULL)
            OR (decision <> 'PENDING' AND decided_at IS NOT NULL AND decided_by IS NOT NULL))
);

CREATE TABLE sys_access_remediation_tasks (
    access_remediation_task_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    access_review_item_id UUID NOT NULL UNIQUE
        REFERENCES com_access_review_items(access_review_item_id),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    action_type VARCHAR(40) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    reason VARCHAR(1000) NOT NULL,
    completed_at TIMESTAMPTZ,
    completed_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_access_remediation_action
        CHECK (action_type IN ('REMOVE_DIRECT_ROLE', 'REVIEW_GROUP_MEMBERSHIP')),
    CONSTRAINT ck_access_remediation_state
        CHECK (lifecycle_state IN ('OPEN', 'COMPLETED', 'FAILED'))
);

CREATE TABLE sys_identity_lifecycle_events (
    identity_lifecycle_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    user_id BIGINT NOT NULL,
    person_public_id UUID NOT NULL,
    lifecycle_type VARCHAR(20) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    source_version VARCHAR(255),
    processing_state VARCHAR(20) NOT NULL DEFAULT 'APPLIED',
    change_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id VARCHAR(128),
    CONSTRAINT fk_identity_lifecycle_user
        FOREIGN KEY (tenant_id, user_id)
        REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT ck_identity_lifecycle_type
        CHECK (lifecycle_type IN ('JOINER', 'MOVER', 'LEAVER', 'REHIRE', 'UPDATE')),
    CONSTRAINT ck_identity_lifecycle_source
        CHECK (source_type IN ('HRIS', 'SCIM', 'LOCAL')),
    CONSTRAINT ck_identity_lifecycle_state
        CHECK (processing_state IN ('APPLIED', 'PARTIAL', 'FAILED'))
);

CREATE INDEX idx_access_review_campaign_tenant_state
    ON com_access_review_campaigns(tenant_id, lifecycle_state, due_at);
CREATE INDEX idx_access_review_item_campaign_decision
    ON com_access_review_items(access_review_campaign_id, decision, remediation_state);
CREATE INDEX idx_access_review_item_subject
    ON com_access_review_items(tenant_id, subject_user_id, role_id);
CREATE INDEX idx_access_remediation_tenant_state
    ON sys_access_remediation_tasks(tenant_id, lifecycle_state, created_at);
CREATE INDEX idx_identity_lifecycle_tenant_time
    ON sys_identity_lifecycle_events(tenant_id, occurred_at DESC);
CREATE INDEX idx_identity_lifecycle_person_time
    ON sys_identity_lifecycle_events(tenant_id, person_public_id, occurred_at DESC);
