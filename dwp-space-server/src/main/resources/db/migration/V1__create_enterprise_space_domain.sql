CREATE TABLE spc_tenants (
    tenant_id BIGINT PRIMARY KEY,
    creation_policy VARCHAR(24) NOT NULL DEFAULT 'POLICY_DRIVEN',
    external_sharing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    default_retention_days INTEGER NOT NULL DEFAULT 365,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_spc_tenant_creation_policy CHECK (
        creation_policy IN ('OPEN', 'POLICY_DRIVEN', 'ADMIN_ONLY')),
    CONSTRAINT ck_spc_tenant_retention CHECK (default_retention_days BETWEEN 30 AND 3650),
    CONSTRAINT ck_spc_tenant_state CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE TABLE spc_templates (
    template_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES spc_tenants(tenant_id),
    template_key VARCHAR(100) NOT NULL,
    name_ko VARCHAR(200) NOT NULL,
    name_en VARCHAR(200) NOT NULL,
    description_ko VARCHAR(1000) NOT NULL,
    description_en VARCHAR(1000) NOT NULL,
    purpose_type VARCHAR(30) NOT NULL,
    creation_mode VARCHAR(20) NOT NULL DEFAULT 'POLICY',
    default_visibility VARCHAR(20) NOT NULL DEFAULT 'REQUEST',
    default_data_classification VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    default_member_role VARCHAR(20) NOT NULL DEFAULT 'VIEWER',
    allowed_content_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    default_apps JSONB NOT NULL DEFAULT '[]'::jsonb,
    icon_key VARCHAR(60) NOT NULL DEFAULT 'layers-3',
    accent_token VARCHAR(30) NOT NULL DEFAULT 'indigo',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    current_version INTEGER NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_spc_template_key UNIQUE (tenant_id, template_key),
    CONSTRAINT uk_spc_template_scope UNIQUE (tenant_id, template_id),
    CONSTRAINT ck_spc_template_purpose CHECK (
        purpose_type IN ('PROJECT', 'COMMUNITY', 'OPERATIONS', 'KNOWLEDGE', 'LEADERSHIP')),
    CONSTRAINT ck_spc_template_creation CHECK (creation_mode IN ('AUTO', 'POLICY', 'APPROVAL')),
    CONSTRAINT ck_spc_template_visibility CHECK (
        default_visibility IN ('OPEN', 'REQUEST', 'PRIVATE', 'HIDDEN')),
    CONSTRAINT ck_spc_template_classification CHECK (
        default_data_classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_spc_template_member_role CHECK (
        default_member_role IN ('VIEWER', 'CONTRIBUTOR', 'EDITOR')),
    CONSTRAINT ck_spc_template_contents CHECK (jsonb_typeof(allowed_content_types) = 'array'),
    CONSTRAINT ck_spc_template_apps CHECK (jsonb_typeof(default_apps) = 'array'),
    CONSTRAINT ck_spc_template_state CHECK (lifecycle_state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_spc_template_version CHECK (current_version > 0)
);

CREATE TABLE spc_spaces (
    space_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES spc_tenants(tenant_id),
    template_id UUID,
    space_key VARCHAR(100) NOT NULL,
    name_ko VARCHAR(200) NOT NULL,
    name_en VARCHAR(200) NOT NULL,
    summary_ko VARCHAR(1200) NOT NULL DEFAULT '',
    summary_en VARCHAR(1200) NOT NULL DEFAULT '',
    purpose_type VARCHAR(30) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    data_classification VARCHAR(20) NOT NULL,
    content_policy VARCHAR(24) NOT NULL DEFAULT 'OWNER_REVIEW',
    app_policy VARCHAR(24) NOT NULL DEFAULT 'OWNER_REVIEW',
    ai_policy VARCHAR(24) NOT NULL DEFAULT 'MEMBER_SCOPED',
    icon_key VARCHAR(60) NOT NULL DEFAULT 'layers-3',
    accent_token VARCHAR(30) NOT NULL DEFAULT 'indigo',
    cover_asset_url VARCHAR(800),
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    activated_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_spc_space_key UNIQUE (tenant_id, space_key),
    CONSTRAINT uk_spc_space_scope UNIQUE (tenant_id, space_id),
    CONSTRAINT fk_spc_space_template FOREIGN KEY (tenant_id, template_id)
        REFERENCES spc_templates(tenant_id, template_id),
    CONSTRAINT ck_spc_space_purpose CHECK (
        purpose_type IN ('PROJECT', 'COMMUNITY', 'OPERATIONS', 'KNOWLEDGE', 'LEADERSHIP')),
    CONSTRAINT ck_spc_space_visibility CHECK (visibility IN ('OPEN', 'REQUEST', 'PRIVATE', 'HIDDEN')),
    CONSTRAINT ck_spc_space_classification CHECK (
        data_classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_spc_space_content_policy CHECK (
        content_policy IN ('OPEN_PUBLISH', 'OWNER_REVIEW', 'COMPLIANCE_REVIEW')),
    CONSTRAINT ck_spc_space_app_policy CHECK (
        app_policy IN ('OWNER_MANAGED', 'OWNER_REVIEW', 'ADMIN_REVIEW')),
    CONSTRAINT ck_spc_space_ai_policy CHECK (
        ai_policy IN ('DISABLED', 'MEMBER_SCOPED', 'RESTRICTED_SCOPED')),
    CONSTRAINT ck_spc_space_state CHECK (
        lifecycle_state IN ('DRAFT', 'ACTIVE', 'ARCHIVED', 'DELETION_PENDING', 'DELETED'))
);

CREATE TABLE spc_space_requests (
    request_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES spc_tenants(tenant_id),
    template_id UUID NOT NULL,
    requester_user_id BIGINT NOT NULL,
    requester_person_public_id UUID,
    requester_name VARCHAR(200),
    requested_key VARCHAR(100) NOT NULL,
    requested_name VARCHAR(200) NOT NULL,
    requested_summary VARCHAR(1200) NOT NULL,
    requested_visibility VARCHAR(20) NOT NULL,
    justification VARCHAR(2000) NOT NULL,
    decision_mode VARCHAR(20) NOT NULL,
    risk_level VARCHAR(20) NOT NULL DEFAULT 'LOW',
    policy_evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    decided_by BIGINT,
    decision_note VARCHAR(2000),
    decided_at TIMESTAMPTZ,
    provisioned_space_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_spc_request_key UNIQUE (tenant_id, request_id),
    CONSTRAINT fk_spc_request_template FOREIGN KEY (tenant_id, template_id)
        REFERENCES spc_templates(tenant_id, template_id),
    CONSTRAINT fk_spc_request_space FOREIGN KEY (tenant_id, provisioned_space_id)
        REFERENCES spc_spaces(tenant_id, space_id),
    CONSTRAINT ck_spc_request_visibility CHECK (
        requested_visibility IN ('OPEN', 'REQUEST', 'PRIVATE', 'HIDDEN')),
    CONSTRAINT ck_spc_request_decision_mode CHECK (decision_mode IN ('AUTO', 'POLICY', 'APPROVAL')),
    CONSTRAINT ck_spc_request_risk CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_spc_request_evidence CHECK (jsonb_typeof(policy_evidence) = 'object'),
    CONSTRAINT ck_spc_request_state CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'PROVISIONED')),
    CONSTRAINT ck_spc_request_decision CHECK (
        (status = 'PENDING' AND decided_at IS NULL)
        OR (status <> 'PENDING' AND status = 'CANCELLED')
        OR (status IN ('APPROVED', 'REJECTED', 'PROVISIONED') AND decided_at IS NOT NULL))
);

CREATE TABLE spc_memberships (
    membership_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    space_id UUID NOT NULL,
    principal_type VARCHAR(20) NOT NULL,
    principal_ref VARCHAR(200) NOT NULL,
    member_role VARCHAR(20) NOT NULL,
    membership_source VARCHAR(24) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    valid_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until TIMESTAMPTZ,
    approved_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_spc_membership UNIQUE (tenant_id, space_id, principal_type, principal_ref),
    CONSTRAINT fk_spc_membership_space FOREIGN KEY (tenant_id, space_id)
        REFERENCES spc_spaces(tenant_id, space_id),
    CONSTRAINT ck_spc_membership_principal CHECK (principal_type IN ('USER', 'GROUP')),
    CONSTRAINT ck_spc_membership_role CHECK (
        member_role IN ('VIEWER', 'CONTRIBUTOR', 'EDITOR', 'MODERATOR', 'OWNER', 'GUEST')),
    CONSTRAINT ck_spc_membership_source CHECK (
        membership_source IN ('DIRECT', 'GROUP', 'REQUEST', 'TEMPLATE', 'PROVISIONING')),
    CONSTRAINT ck_spc_membership_state CHECK (
        lifecycle_state IN ('PENDING', 'ACTIVE', 'EXPIRED', 'REVOKED')),
    CONSTRAINT ck_spc_membership_window CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE TABLE spc_principal_cardinalities (
    tenant_id BIGINT NOT NULL REFERENCES spc_tenants(tenant_id),
    principal_type VARCHAR(20) NOT NULL,
    principal_ref VARCHAR(200) NOT NULL,
    active_principal_count INTEGER NOT NULL,
    source_system VARCHAR(40) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, principal_type, principal_ref),
    CONSTRAINT ck_spc_principal_cardinality_type CHECK (principal_type IN ('USER', 'GROUP')),
    CONSTRAINT ck_spc_principal_cardinality_count CHECK (active_principal_count >= 0)
);

CREATE TABLE spc_content_items (
    content_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    space_id UUID NOT NULL,
    content_type VARCHAR(24) NOT NULL,
    title VARCHAR(300) NOT NULL,
    summary VARCHAR(2000) NOT NULL DEFAULT '',
    route VARCHAR(800),
    data_classification VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    author_user_id BIGINT NOT NULL,
    author_name VARCHAR(200),
    current_revision INTEGER NOT NULL DEFAULT 1,
    published_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_spc_content_scope UNIQUE (tenant_id, content_id),
    CONSTRAINT fk_spc_content_space FOREIGN KEY (tenant_id, space_id)
        REFERENCES spc_spaces(tenant_id, space_id),
    CONSTRAINT ck_spc_content_type CHECK (
        content_type IN ('PAGE', 'POST', 'FILE', 'LINK', 'CANVAS', 'DECISION', 'APP_EMBED')),
    CONSTRAINT ck_spc_content_classification CHECK (
        data_classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_spc_content_state CHECK (
        lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'ARCHIVED', 'REJECTED')),
    CONSTRAINT ck_spc_content_revision CHECK (current_revision > 0)
);

CREATE TABLE spc_content_revisions (
    revision_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    content_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    content_payload JSONB NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_spc_content_revision UNIQUE (tenant_id, content_id, revision_number),
    CONSTRAINT fk_spc_content_revision_item FOREIGN KEY (tenant_id, content_id)
        REFERENCES spc_content_items(tenant_id, content_id),
    CONSTRAINT ck_spc_content_payload CHECK (jsonb_typeof(content_payload) = 'object')
);

CREATE TABLE spc_publication_reviews (
    review_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    space_id UUID NOT NULL,
    content_id UUID NOT NULL,
    requested_by BIGINT NOT NULL,
    reviewer_strategy VARCHAR(24) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    decision_note VARCHAR(2000),
    decided_by BIGINT,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_spc_publication_review UNIQUE (tenant_id, review_id),
    CONSTRAINT fk_spc_review_space FOREIGN KEY (tenant_id, space_id)
        REFERENCES spc_spaces(tenant_id, space_id),
    CONSTRAINT fk_spc_review_content FOREIGN KEY (tenant_id, content_id)
        REFERENCES spc_content_items(tenant_id, content_id),
    CONSTRAINT ck_spc_review_strategy CHECK (
        reviewer_strategy IN ('SPACE_OWNER', 'SPACE_MODERATOR', 'COMPLIANCE')),
    CONSTRAINT ck_spc_review_state CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

CREATE TABLE spc_app_bindings (
    binding_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    space_id UUID NOT NULL,
    app_key VARCHAR(120) NOT NULL,
    display_name_ko VARCHAR(200) NOT NULL,
    display_name_en VARCHAR(200) NOT NULL,
    launch_target VARCHAR(800) NOT NULL,
    icon_key VARCHAR(60) NOT NULL,
    data_access_scope VARCHAR(24) NOT NULL DEFAULT 'SPACE_ONLY',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_spc_app_binding UNIQUE (tenant_id, space_id, app_key),
    CONSTRAINT fk_spc_app_space FOREIGN KEY (tenant_id, space_id)
        REFERENCES spc_spaces(tenant_id, space_id),
    CONSTRAINT ck_spc_app_scope CHECK (
        data_access_scope IN ('SPACE_ONLY', 'TENANT_READ', 'EXPLICIT_RESOURCE')),
    CONSTRAINT ck_spc_app_state CHECK (lifecycle_state IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE TABLE spc_activity_events (
    activity_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    space_id UUID NOT NULL,
    activity_type VARCHAR(40) NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_ref VARCHAR(200),
    actor_name VARCHAR(200),
    object_type VARCHAR(40) NOT NULL,
    object_ref VARCHAR(200) NOT NULL,
    title_ko VARCHAR(500) NOT NULL,
    title_en VARCHAR(500) NOT NULL,
    route VARCHAR(800),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_spc_activity_space FOREIGN KEY (tenant_id, space_id)
        REFERENCES spc_spaces(tenant_id, space_id),
    CONSTRAINT ck_spc_activity_actor CHECK (actor_type IN ('USER', 'AGENT', 'SYSTEM'))
);

CREATE TABLE spc_lifecycle_reviews (
    lifecycle_review_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    space_id UUID NOT NULL,
    review_type VARCHAR(24) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    recommendation VARCHAR(24),
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    decided_by BIGINT,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_spc_lifecycle_review UNIQUE (tenant_id, lifecycle_review_id),
    CONSTRAINT fk_spc_lifecycle_space FOREIGN KEY (tenant_id, space_id)
        REFERENCES spc_spaces(tenant_id, space_id),
    CONSTRAINT ck_spc_lifecycle_type CHECK (review_type IN ('ACTIVITY', 'ACCESS', 'RETENTION')),
    CONSTRAINT ck_spc_lifecycle_state CHECK (status IN ('OPEN', 'COMPLETED', 'OVERDUE', 'CANCELLED')),
    CONSTRAINT ck_spc_lifecycle_recommendation CHECK (
        recommendation IS NULL OR recommendation IN ('KEEP', 'ARCHIVE', 'DELETE', 'REVIEW_ACCESS')),
    CONSTRAINT ck_spc_lifecycle_evidence CHECK (jsonb_typeof(evidence) = 'object')
);

CREATE TABLE sys_audit_outbox (
    outbox_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(255),
    locked_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_spc_audit_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_spc_audit_outbox_status CHECK (
        status IN ('PENDING', 'SENDING', 'FAILED', 'PUBLISHED', 'DEAD')),
    CONSTRAINT ck_spc_audit_outbox_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_spc_space_discovery
    ON spc_spaces (tenant_id, lifecycle_state, visibility, last_activity_at DESC);
CREATE INDEX idx_spc_membership_principal
    ON spc_memberships (tenant_id, principal_type, principal_ref, lifecycle_state, valid_until);
CREATE INDEX idx_spc_request_queue
    ON spc_space_requests (tenant_id, status, risk_level, created_at);
CREATE INDEX idx_spc_content_feed
    ON spc_content_items (tenant_id, space_id, lifecycle_state, updated_at DESC);
CREATE INDEX idx_spc_review_queue
    ON spc_publication_reviews (tenant_id, status, created_at);
CREATE INDEX idx_spc_activity_feed
    ON spc_activity_events (tenant_id, space_id, occurred_at DESC);
CREATE INDEX idx_spc_lifecycle_queue
    ON spc_lifecycle_reviews (tenant_id, status, due_at);
CREATE INDEX idx_spc_audit_delivery
    ON sys_audit_outbox (status, available_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'SENDING');

COMMENT ON TABLE spc_spaces IS
    'Tenant-scoped collaboration spaces. Purpose, visibility, policy and lifecycle are independent dimensions.';
COMMENT ON TABLE spc_memberships IS
    'Direct and group-derived Space roles. Runtime authorization resolves the strongest active role.';
COMMENT ON TABLE spc_publication_reviews IS
    'Content publication workflow selected by each Space content policy.';
