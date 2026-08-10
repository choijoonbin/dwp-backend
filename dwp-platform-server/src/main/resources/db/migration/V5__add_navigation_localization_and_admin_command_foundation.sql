ALTER TABLE adm_reference_sets
    ADD CONSTRAINT uk_adm_reference_sets_tenant_id
        UNIQUE (tenant_id, reference_set_id);

ALTER TABLE adm_reference_items
    ADD COLUMN parent_reference_item_id BIGINT,
    ADD CONSTRAINT uk_adm_reference_items_tenant_id
        UNIQUE (tenant_id, reference_item_id),
    ADD CONSTRAINT uk_adm_reference_items_tenant_set_id
        UNIQUE (tenant_id, reference_set_id, reference_item_id),
    ADD CONSTRAINT fk_adm_reference_items_set_tenant
        FOREIGN KEY (tenant_id, reference_set_id)
        REFERENCES adm_reference_sets(tenant_id, reference_set_id),
    ADD CONSTRAINT fk_adm_reference_items_parent
        FOREIGN KEY (tenant_id, reference_set_id, parent_reference_item_id)
        REFERENCES adm_reference_items(tenant_id, reference_set_id, reference_item_id),
    ADD CONSTRAINT ck_adm_reference_items_not_self
        CHECK (parent_reference_item_id <> reference_item_id);

UPDATE adm_reference_items child
SET parent_reference_item_id = parent.reference_item_id
FROM adm_reference_items parent
WHERE child.tenant_id = parent.tenant_id
  AND child.reference_set_id = parent.reference_set_id
  AND child.parent_code = parent.code;

CREATE INDEX idx_adm_reference_items_parent
    ON adm_reference_items(tenant_id, reference_set_id, parent_reference_item_id, sort_order);

ALTER TABLE adm_reference_item_labels
    ALTER COLUMN locale TYPE VARCHAR(35),
    ADD CONSTRAINT fk_adm_reference_item_labels_item_tenant
        FOREIGN KEY (tenant_id, reference_item_id)
        REFERENCES adm_reference_items(tenant_id, reference_item_id) ON DELETE CASCADE;

CREATE TABLE adm_tenant_locales (
    tenant_locale_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    locale VARCHAR(35) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    default_locale BOOLEAN NOT NULL DEFAULT FALSE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    sort_order INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_tenant_locales_locale UNIQUE (tenant_id, locale),
    CONSTRAINT ck_adm_tenant_locales_state
        CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX uk_adm_tenant_locales_default
    ON adm_tenant_locales(tenant_id)
    WHERE default_locale = TRUE;

CREATE TABLE adm_message_overrides (
    message_override_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    namespace VARCHAR(100) NOT NULL,
    message_key VARCHAR(200) NOT NULL,
    locale VARCHAR(35) NOT NULL,
    message_value TEXT NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_message_overrides_key
        UNIQUE (tenant_id, namespace, message_key, locale),
    CONSTRAINT ck_adm_message_overrides_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_adm_message_overrides_namespace
        CHECK (namespace IN ('TENANT', 'NAVIGATION', 'SERVICE', 'CONTENT'))
);

CREATE TABLE adm_navigation_items (
    navigation_item_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    navigation_key VARCHAR(120) NOT NULL,
    item_type VARCHAR(20) NOT NULL,
    parent_navigation_item_id BIGINT,
    registry_entry_key VARCHAR(100),
    route VARCHAR(500),
    icon_key VARCHAR(80),
    required_resource_key VARCHAR(255),
    required_permission_code VARCHAR(50) NOT NULL DEFAULT 'VIEW',
    sort_order INTEGER NOT NULL DEFAULT 0,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_navigation_items_key UNIQUE (tenant_id, navigation_key),
    CONSTRAINT uk_adm_navigation_items_tenant_id UNIQUE (tenant_id, navigation_item_id),
    CONSTRAINT fk_adm_navigation_items_parent
        FOREIGN KEY (tenant_id, parent_navigation_item_id)
        REFERENCES adm_navigation_items(tenant_id, navigation_item_id),
    CONSTRAINT ck_adm_navigation_items_type CHECK (item_type IN ('GROUP', 'APP')),
    CONSTRAINT ck_adm_navigation_items_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_adm_navigation_items_not_self
        CHECK (parent_navigation_item_id <> navigation_item_id),
    CONSTRAINT ck_adm_navigation_items_shape CHECK (
        (item_type = 'GROUP' AND registry_entry_key IS NULL AND route IS NULL)
        OR (
            item_type = 'APP'
            AND registry_entry_key IS NOT NULL
            AND route LIKE '/%'
            AND required_resource_key IS NOT NULL
        )
    )
);

CREATE TABLE adm_navigation_labels (
    navigation_label_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    navigation_item_id BIGINT NOT NULL,
    locale VARCHAR(35) NOT NULL,
    label VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_adm_navigation_labels_item
        FOREIGN KEY (tenant_id, navigation_item_id)
        REFERENCES adm_navigation_items(tenant_id, navigation_item_id) ON DELETE CASCADE,
    CONSTRAINT uk_adm_navigation_labels_locale
        UNIQUE (tenant_id, navigation_item_id, locale)
);

CREATE INDEX idx_adm_navigation_items_runtime
    ON adm_navigation_items(tenant_id, lifecycle_state, parent_navigation_item_id, sort_order);

CREATE TABLE sys_admin_command_requests (
    command_request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id VARCHAR(160) NOT NULL,
    delegated_user_id BIGINT,
    command_key VARCHAR(120) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(160) NOT NULL,
    expected_version BIGINT NOT NULL,
    redacted_parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    justification VARCHAR(1000) NOT NULL,
    plan_hash CHAR(64) NOT NULL,
    risk_tier VARCHAR(20) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'PREVIEWED',
    correlation_id VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    executed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_admin_command_tenant_id UNIQUE (tenant_id, command_request_id),
    CONSTRAINT uk_sys_admin_command_correlation UNIQUE (tenant_id, correlation_id),
    CONSTRAINT uk_sys_admin_command_plan UNIQUE (tenant_id, plan_hash),
    CONSTRAINT ck_sys_admin_command_actor CHECK (actor_type IN ('USER', 'AGENT')),
    CONSTRAINT ck_sys_admin_command_risk CHECK (risk_tier IN ('L0', 'L1', 'L2', 'L3')),
    CONSTRAINT ck_sys_admin_command_state CHECK (
        lifecycle_state IN (
            'PREVIEWED', 'PENDING_APPROVAL', 'APPROVED', 'DENIED',
            'EXECUTED', 'FAILED', 'EXPIRED', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_sys_admin_command_expiry CHECK (expires_at > created_at)
);

CREATE TABLE sys_admin_command_approvals (
    command_approval_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    command_request_id UUID NOT NULL,
    step_number INTEGER NOT NULL,
    approver_type VARCHAR(20) NOT NULL,
    approver_ref VARCHAR(160) NOT NULL,
    decision VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    decision_reason VARCHAR(1000),
    decided_by BIGINT,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sys_admin_command_approvals_request
        FOREIGN KEY (tenant_id, command_request_id)
        REFERENCES sys_admin_command_requests(tenant_id, command_request_id),
    CONSTRAINT uk_sys_admin_command_approvals_step
        UNIQUE (tenant_id, command_request_id, step_number, approver_type, approver_ref),
    CONSTRAINT ck_sys_admin_command_approver
        CHECK (approver_type IN ('USER', 'GROUP', 'ROLE')),
    CONSTRAINT ck_sys_admin_command_decision
        CHECK (decision IN ('PENDING', 'APPROVED', 'DENIED', 'CANCELLED')),
    CONSTRAINT ck_sys_admin_command_step CHECK (step_number > 0)
);

CREATE INDEX idx_adm_message_overrides_runtime
    ON adm_message_overrides(tenant_id, locale, lifecycle_state, namespace, message_key);
CREATE INDEX idx_sys_admin_command_queue
    ON sys_admin_command_requests(tenant_id, lifecycle_state, created_at DESC);
