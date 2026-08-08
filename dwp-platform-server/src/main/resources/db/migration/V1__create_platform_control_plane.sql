CREATE TABLE adm_reference_sets (
    reference_set_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    set_key VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    content_revision BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_reference_sets_tenant_key UNIQUE (tenant_id, set_key),
    CONSTRAINT ck_adm_reference_sets_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE adm_reference_items (
    reference_item_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    reference_set_id BIGINT NOT NULL REFERENCES adm_reference_sets(reference_set_id),
    code VARCHAR(80) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    sort_order INTEGER NOT NULL DEFAULT 0,
    parent_code VARCHAR(80),
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_reference_items_set_code
        UNIQUE (tenant_id, reference_set_id, code),
    CONSTRAINT ck_adm_reference_items_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_adm_reference_items_validity
        CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from)
);

CREATE TABLE adm_reference_item_labels (
    reference_item_label_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    reference_item_id BIGINT NOT NULL REFERENCES adm_reference_items(reference_item_id)
        ON DELETE CASCADE,
    locale VARCHAR(20) NOT NULL,
    label VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_reference_item_labels_locale
        UNIQUE (tenant_id, reference_item_id, locale)
);

CREATE TABLE sys_platform_audit_events (
    audit_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id BIGINT,
    action VARCHAR(120) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(160) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    correlation_id VARCHAR(128),
    before_snapshot TEXT,
    after_snapshot TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_platform_audit_actor CHECK (actor_type IN ('USER', 'SERVICE')),
    CONSTRAINT ck_sys_platform_audit_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED'))
);

CREATE INDEX idx_adm_reference_sets_tenant_state
    ON adm_reference_sets (tenant_id, lifecycle_state, set_key);
CREATE INDEX idx_adm_reference_items_set_order
    ON adm_reference_items (tenant_id, reference_set_id, sort_order, code);
CREATE INDEX idx_adm_reference_items_runtime
    ON adm_reference_items (tenant_id, reference_set_id, lifecycle_state, valid_from, valid_to);
CREATE INDEX idx_sys_platform_audit_tenant_time
    ON sys_platform_audit_events (tenant_id, occurred_at DESC);
