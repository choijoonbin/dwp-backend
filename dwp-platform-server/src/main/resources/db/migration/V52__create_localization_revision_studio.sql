CREATE TABLE adm_localization_bundles (
    localization_bundle_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    bundle_key VARCHAR(120) NOT NULL,
    source_locale VARCHAR(35) NOT NULL,
    target_locale VARCHAR(35) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    current_published_revision_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_adm_localization_bundle UNIQUE (tenant_id, bundle_key, target_locale),
    CONSTRAINT ck_adm_localization_bundle_key
        CHECK (bundle_key ~ '^[a-z][a-z0-9.-]{2,119}$'),
    CONSTRAINT ck_adm_localization_source_locale
        CHECK (source_locale ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'),
    CONSTRAINT ck_adm_localization_target_locale
        CHECK (target_locale ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'),
    CONSTRAINT ck_adm_localization_distinct_locale
        CHECK (LOWER(source_locale) <> LOWER(target_locale)),
    CONSTRAINT ck_adm_localization_bundle_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE adm_localization_revisions (
    localization_revision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    localization_bundle_id UUID NOT NULL
        REFERENCES adm_localization_bundles(localization_bundle_id),
    tenant_id BIGINT NOT NULL,
    revision_number BIGINT NOT NULL,
    based_on_revision_id UUID
        REFERENCES adm_localization_revisions(localization_revision_id),
    source_entries JSONB NOT NULL,
    entries JSONB NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    change_summary VARCHAR(1000) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    submitted_by BIGINT,
    submitted_at TIMESTAMPTZ,
    decided_by BIGINT,
    decided_at TIMESTAMPTZ,
    published_by BIGINT,
    published_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_adm_localization_revision UNIQUE (localization_bundle_id, revision_number),
    CONSTRAINT ck_adm_localization_source_entries
        CHECK (jsonb_typeof(source_entries) = 'object' AND source_entries <> '{}'::jsonb),
    CONSTRAINT ck_adm_localization_entries
        CHECK (jsonb_typeof(entries) = 'object'),
    CONSTRAINT ck_adm_localization_revision_state
        CHECK (lifecycle_state IN (
            'DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT ck_adm_localization_revision_summary CHECK (BTRIM(change_summary) <> ''),
    CONSTRAINT ck_adm_localization_revision_hash
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_adm_localization_revision_submission
        CHECK ((submitted_by IS NULL AND submitted_at IS NULL)
            OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_adm_localization_revision_decision
        CHECK ((decided_by IS NULL AND decided_at IS NULL)
            OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_adm_localization_revision_publish
        CHECK ((published_by IS NULL AND published_at IS NULL)
            OR (published_by IS NOT NULL AND published_at IS NOT NULL))
);

ALTER TABLE adm_localization_bundles
    ADD CONSTRAINT fk_adm_localization_current_revision
    FOREIGN KEY (current_published_revision_id)
    REFERENCES adm_localization_revisions(localization_revision_id);

CREATE UNIQUE INDEX uk_adm_localization_open_revision
    ON adm_localization_revisions(localization_bundle_id)
    WHERE lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED');

CREATE INDEX idx_adm_localization_revision_tenant
    ON adm_localization_revisions(tenant_id, lifecycle_state, updated_at DESC);

CREATE TABLE adm_localization_revision_decisions (
    localization_revision_decision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    localization_revision_id UUID NOT NULL
        REFERENCES adm_localization_revisions(localization_revision_id),
    tenant_id BIGINT NOT NULL,
    previous_state VARCHAR(24) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    actor_id BIGINT NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_adm_localization_decision
        CHECK (decision IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'PUBLISHED', 'RESTORED')),
    CONSTRAINT ck_adm_localization_decision_reason CHECK (BTRIM(reason) <> '')
);

CREATE OR REPLACE FUNCTION sys_guard_localization_revision_content()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.lifecycle_state <> 'DRAFT'
       AND (NEW.source_entries IS DISTINCT FROM OLD.source_entries
            OR NEW.entries IS DISTINCT FROM OLD.entries
            OR NEW.change_summary IS DISTINCT FROM OLD.change_summary
            OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256) THEN
        RAISE EXCEPTION 'Submitted localization revision content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_adm_localization_revision_content_immutable
BEFORE UPDATE ON adm_localization_revisions
FOR EACH ROW EXECUTE FUNCTION sys_guard_localization_revision_content();

CREATE OR REPLACE FUNCTION sys_reject_localization_decision_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Localization decisions are append-only';
END;
$$;

CREATE TRIGGER trg_adm_localization_decisions_immutable
BEFORE UPDATE OR DELETE ON adm_localization_revision_decisions
FOR EACH ROW EXECUTE FUNCTION sys_reject_localization_decision_mutation();

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.LOCALIZATION.REVISION_STATE', 'dwp-platform-server',
     'Localization revision state',
     'Governed lifecycle of a tenant localization revision.',
     'SYSTEM', 'CHECK', 'adm_localization_revisions.lifecycle_state', 'STATE_MACHINE'),
    ('PLATFORM.LOCALIZATION.DECISION', 'dwp-platform-server',
     'Localization revision decision',
     'Append-only transition evidence for localization review and publication.',
     'SYSTEM', 'CHECK', 'adm_localization_revision_decisions.decision', 'PROTOCOL');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.LOCALIZATION.REVISION_STATE', 'DRAFT', 'Draft',
     '{"ko":"초안","en":"Draft"}', 10, '{"terminal":false}'),
    ('PLATFORM.LOCALIZATION.REVISION_STATE', 'IN_REVIEW', 'In review',
     '{"ko":"검토 중","en":"In review"}', 20, '{"terminal":false}'),
    ('PLATFORM.LOCALIZATION.REVISION_STATE', 'APPROVED', 'Approved',
     '{"ko":"승인","en":"Approved"}', 30, '{"terminal":false}'),
    ('PLATFORM.LOCALIZATION.REVISION_STATE', 'REJECTED', 'Rejected',
     '{"ko":"반려","en":"Rejected"}', 40, '{"terminal":true}'),
    ('PLATFORM.LOCALIZATION.REVISION_STATE', 'PUBLISHED', 'Published',
     '{"ko":"게시됨","en":"Published"}', 50, '{"terminal":true}'),
    ('PLATFORM.LOCALIZATION.REVISION_STATE', 'SUPERSEDED', 'Superseded',
     '{"ko":"대체됨","en":"Superseded"}', 60, '{"terminal":true}'),
    ('PLATFORM.LOCALIZATION.DECISION', 'SUBMITTED', 'Submitted',
     '{"ko":"검토 제출","en":"Submitted"}', 10, '{}'),
    ('PLATFORM.LOCALIZATION.DECISION', 'APPROVED', 'Approved',
     '{"ko":"승인","en":"Approved"}', 20, '{}'),
    ('PLATFORM.LOCALIZATION.DECISION', 'REJECTED', 'Rejected',
     '{"ko":"반려","en":"Rejected"}', 30, '{}'),
    ('PLATFORM.LOCALIZATION.DECISION', 'PUBLISHED', 'Published',
     '{"ko":"게시","en":"Published"}', 40, '{}'),
    ('PLATFORM.LOCALIZATION.DECISION', 'RESTORED', 'Restored as draft',
     '{"ko":"복원 초안 생성","en":"Restored as draft"}', 50, '{}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.LOCALIZATION.REVISION_STATE', 'dwp-platform-server', 'DATABASE_COLUMN',
     'adm_localization_revisions.lifecycle_state', 'CHECK'),
    ('PLATFORM.LOCALIZATION.REVISION_STATE', 'dwp-frontend', 'UI_SELECTION',
     'admin localization revision state', 'CATALOG_LOOKUP'),
    ('PLATFORM.LOCALIZATION.DECISION', 'dwp-platform-server', 'API_CONTRACT',
     'LocalizationDtos.DecisionRequest', 'TYPED_CONTRACT');

COMMENT ON TABLE adm_localization_bundles IS
    'Tenant-owned localization bundle identity and current published revision pointer.';
COMMENT ON TABLE adm_localization_revisions IS
    'Versioned translation content with independent review and immutable publication history.';
COMMENT ON TABLE adm_localization_revision_decisions IS
    'Append-only evidence for localization submission, decision, publication, and restore actions.';
