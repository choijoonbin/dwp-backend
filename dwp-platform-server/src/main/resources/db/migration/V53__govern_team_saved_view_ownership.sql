ALTER TABLE usr_saved_views
    DROP CONSTRAINT ck_usr_saved_views_scope;

ALTER TABLE usr_saved_views
    ALTER COLUMN owner_user_id DROP NOT NULL,
    ADD COLUMN owner_group_ref UUID,
    ADD COLUMN lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN retention_until TIMESTAMPTZ,
    ADD CONSTRAINT ck_usr_saved_views_scope
        CHECK (scope IN ('PERSONAL', 'TEAM', 'TENANT')),
    ADD CONSTRAINT ck_usr_saved_views_team_owner
        CHECK ((scope = 'TEAM' AND owner_group_ref IS NOT NULL)
            OR (scope <> 'TEAM' AND owner_group_ref IS NULL)),
    ADD CONSTRAINT ck_usr_saved_views_lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'ORPHANED', 'ARCHIVED')
            AND ((lifecycle_state = 'ACTIVE' AND owner_user_id IS NOT NULL)
                OR lifecycle_state <> 'ACTIVE')),
    ADD CONSTRAINT ck_usr_saved_views_retention
        CHECK ((lifecycle_state = 'ORPHANED' AND retention_until IS NOT NULL)
            OR (lifecycle_state <> 'ORPHANED' AND retention_until IS NULL)),
    ADD CONSTRAINT uk_usr_saved_views_tenant_id
        UNIQUE (tenant_id, saved_view_id);

DROP INDEX uk_usr_saved_views_personal_name;
DROP INDEX uk_usr_saved_views_tenant_name;

CREATE UNIQUE INDEX uk_usr_saved_views_personal_name
    ON usr_saved_views (tenant_id, owner_user_id, surface_key, LOWER(name))
    WHERE scope = 'PERSONAL' AND lifecycle_state = 'ACTIVE';

CREATE UNIQUE INDEX uk_usr_saved_views_team_name
    ON usr_saved_views (tenant_id, owner_group_ref, surface_key, LOWER(name))
    WHERE scope = 'TEAM' AND lifecycle_state = 'ACTIVE';

CREATE UNIQUE INDEX uk_usr_saved_views_tenant_name
    ON usr_saved_views (tenant_id, surface_key, LOWER(name))
    WHERE scope = 'TENANT' AND lifecycle_state = 'ACTIVE';

CREATE INDEX idx_usr_saved_views_owner_lifecycle
    ON usr_saved_views (tenant_id, owner_user_id, lifecycle_state, updated_at DESC);

CREATE TABLE usr_saved_view_transfer_batches (
    transfer_batch_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    source_owner_user_id BIGINT NOT NULL,
    target_owner_user_id BIGINT,
    disposition VARCHAR(20) NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    ownership_fingerprint CHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    retention_until TIMESTAMPTZ,
    expected_count INTEGER NOT NULL,
    transferred_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    CONSTRAINT uk_usr_saved_view_transfer_idempotency
        UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_usr_saved_view_transfer_disposition
        CHECK (disposition IN ('TRANSFER', 'RETAIN_ORPHANED')),
    CONSTRAINT ck_usr_saved_view_transfer_target
        CHECK ((disposition = 'TRANSFER' AND target_owner_user_id IS NOT NULL
                AND target_owner_user_id <> source_owner_user_id
                AND retention_until IS NULL)
            OR (disposition = 'RETAIN_ORPHANED' AND target_owner_user_id IS NULL
                AND retention_until IS NOT NULL)),
    CONSTRAINT ck_usr_saved_view_transfer_reason
        CHECK (reason_code IN ('OFFBOARDING', 'TEAM_REORGANIZATION', 'OWNER_CORRECTION')),
    CONSTRAINT ck_usr_saved_view_transfer_counts
        CHECK (expected_count >= 0 AND transferred_count = expected_count)
);

CREATE TABLE usr_saved_view_ownership_transfers (
    ownership_transfer_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_batch_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    saved_view_id UUID NOT NULL,
    previous_owner_user_id BIGINT NOT NULL,
    new_owner_user_id BIGINT,
    previous_lifecycle_state VARCHAR(20) NOT NULL,
    new_lifecycle_state VARCHAR(20) NOT NULL,
    owner_group_ref UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_user_id BIGINT NOT NULL,
    CONSTRAINT fk_usr_saved_view_transfer_batch
        FOREIGN KEY (transfer_batch_id)
        REFERENCES usr_saved_view_transfer_batches(transfer_batch_id),
    CONSTRAINT fk_usr_saved_view_transfer_view
        FOREIGN KEY (tenant_id, saved_view_id)
        REFERENCES usr_saved_views(tenant_id, saved_view_id),
    CONSTRAINT uk_usr_saved_view_transfer_item
        UNIQUE (transfer_batch_id, saved_view_id)
);

CREATE INDEX idx_usr_saved_view_transfers_view
    ON usr_saved_view_ownership_transfers (tenant_id, saved_view_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION prevent_saved_view_transfer_history_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Saved view ownership transfer history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_saved_view_transfer_batch_immutable
BEFORE UPDATE OR DELETE ON usr_saved_view_transfer_batches
FOR EACH ROW EXECUTE FUNCTION prevent_saved_view_transfer_history_mutation();

CREATE TRIGGER trg_saved_view_transfer_item_immutable
BEFORE UPDATE OR DELETE ON usr_saved_view_ownership_transfers
FOR EACH ROW EXECUTE FUNCTION prevent_saved_view_transfer_history_mutation();

UPDATE sys_code_values
   SET sort_order = 30
 WHERE code_set_key = 'PLATFORM.SAVED_VIEW.SCOPE' AND code = 'TENANT';

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES (
    'PLATFORM.SAVED_VIEW.SCOPE', 'TEAM', 'Team',
    '{"ko":"팀 공유","en":"Team"}', 20,
    '{"visibility":"VERIFIED_GROUP_MEMBERS","editPolicy":"OWNER"}');

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.SAVED_VIEW.LIFECYCLE', 'dwp-platform-server',
     'Saved view lifecycle', 'Retention-aware lifecycle for governed saved views.',
     'SYSTEM', 'CHECK', 'usr_saved_views.lifecycle_state', 'STATE_MACHINE'),
    ('PLATFORM.SAVED_VIEW.TRANSFER_REASON', 'dwp-platform-server',
     'Saved view transfer reason', 'Governed reason for custody and retention changes.',
     'SYSTEM', 'CHECK', 'usr_saved_view_transfer_batches.reason_code', 'SECURITY');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.SAVED_VIEW.LIFECYCLE', 'ACTIVE', 'Active',
     '{"ko":"활성","en":"Active"}', 10, '{}'),
    ('PLATFORM.SAVED_VIEW.LIFECYCLE', 'ORPHANED', 'Retained without owner',
     '{"ko":"소유자 없이 보존","en":"Retained without owner"}', 20, '{}'),
    ('PLATFORM.SAVED_VIEW.LIFECYCLE', 'ARCHIVED', 'Archived',
     '{"ko":"보관됨","en":"Archived"}', 30, '{}'),
    ('PLATFORM.SAVED_VIEW.TRANSFER_REASON', 'OFFBOARDING', 'Offboarding',
     '{"ko":"퇴직·이동","en":"Offboarding"}', 10, '{}'),
    ('PLATFORM.SAVED_VIEW.TRANSFER_REASON', 'TEAM_REORGANIZATION', 'Team reorganization',
     '{"ko":"팀 개편","en":"Team reorganization"}', 20, '{}'),
    ('PLATFORM.SAVED_VIEW.TRANSFER_REASON', 'OWNER_CORRECTION', 'Owner correction',
     '{"ko":"소유자 정정","en":"Owner correction"}', 30, '{}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
SELECT code_set_key, owner_service, 'DATABASE_COLUMN', source_reference, 'CHECK'
  FROM sys_code_sets
 WHERE code_set_key IN (
    'PLATFORM.SAVED_VIEW.LIFECYCLE',
    'PLATFORM.SAVED_VIEW.TRANSFER_REASON'
 );

COMMENT ON TABLE usr_saved_view_transfer_batches IS
    'Idempotent custody decision for owner offboarding or team reorganization.';
COMMENT ON TABLE usr_saved_view_ownership_transfers IS
    'Append-only per-view evidence for every saved-view custody change.';
