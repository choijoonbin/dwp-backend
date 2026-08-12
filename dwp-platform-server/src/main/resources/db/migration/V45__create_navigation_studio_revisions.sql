CREATE TABLE adm_navigation_revisions (
    navigation_revision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    revision_number BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    baseline_revision_id UUID,
    baseline_tree_hash CHAR(64) NOT NULL,
    tree_payload JSONB NOT NULL,
    validation_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    change_summary VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    published_at TIMESTAMPTZ,
    published_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_navigation_revision_number
        UNIQUE (tenant_id, revision_number),
    CONSTRAINT uk_adm_navigation_revision_tenant_id
        UNIQUE (tenant_id, navigation_revision_id),
    CONSTRAINT fk_adm_navigation_revision_baseline
        FOREIGN KEY (tenant_id, baseline_revision_id)
        REFERENCES adm_navigation_revisions(tenant_id, navigation_revision_id),
    CONSTRAINT ck_adm_navigation_revision_state
        CHECK (lifecycle_state IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT ck_adm_navigation_revision_tree
        CHECK (jsonb_typeof(tree_payload) = 'array'),
    CONSTRAINT ck_adm_navigation_revision_validation
        CHECK (jsonb_typeof(validation_payload) = 'object'),
    CONSTRAINT ck_adm_navigation_revision_number
        CHECK (revision_number > 0),
    CONSTRAINT ck_adm_navigation_revision_publish
        CHECK (
            (lifecycle_state = 'PUBLISHED' AND published_at IS NOT NULL AND published_by IS NOT NULL)
            OR lifecycle_state <> 'PUBLISHED'
        )
);

CREATE UNIQUE INDEX uk_adm_navigation_revision_draft
    ON adm_navigation_revisions(tenant_id)
    WHERE lifecycle_state = 'DRAFT';
CREATE INDEX idx_adm_navigation_revision_history
    ON adm_navigation_revisions(tenant_id, revision_number DESC);

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES (
    'PLATFORM.NAVIGATION_REVISION.LIFECYCLE', 'dwp-platform-server',
    'Navigation revision lifecycle',
    'Draft, published, superseded, and cancelled states for tenant navigation releases.',
    'SYSTEM', 'CHECK', 'adm_navigation_revisions.lifecycle_state', 'STATE_MACHINE'
);

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.NAVIGATION_REVISION.LIFECYCLE', 'DRAFT', 'Draft',
     '{"ko":"초안","en":"Draft"}', 10, '{"mutable":true}'),
    ('PLATFORM.NAVIGATION_REVISION.LIFECYCLE', 'PUBLISHED', 'Published',
     '{"ko":"게시됨","en":"Published"}', 20, '{"runtime":true}'),
    ('PLATFORM.NAVIGATION_REVISION.LIFECYCLE', 'SUPERSEDED', 'Superseded',
     '{"ko":"이전 버전","en":"Superseded"}', 30, '{"terminal":true}'),
    ('PLATFORM.NAVIGATION_REVISION.LIFECYCLE', 'CANCELLED', 'Cancelled',
     '{"ko":"취소됨","en":"Cancelled"}', 40, '{"terminal":true}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES (
    'PLATFORM.NAVIGATION_REVISION.LIFECYCLE', 'dwp-platform-server',
    'DATABASE_COLUMN', 'adm_navigation_revisions.lifecycle_state', 'CHECK'
);

COMMENT ON TABLE adm_navigation_revisions IS
    'Immutable published runtime navigation snapshots plus one mutable tenant draft.';
COMMENT ON COLUMN adm_navigation_revisions.tree_payload IS
    'Validated navigation contract consumed by runtime only after publication.';
