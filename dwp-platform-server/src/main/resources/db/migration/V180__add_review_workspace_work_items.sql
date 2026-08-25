-- Identity owns review decisions. Platform stores only an idempotent queue projection.
ALTER TABLE wrk_items
    DROP CONSTRAINT ck_wrk_items_type;

ALTER TABLE wrk_items
    ADD CONSTRAINT ck_wrk_items_type
        CHECK (work_type IN ('APPROVAL', 'TASK', 'SERVICE', 'REQUIRED', 'REVIEW')),
    ADD COLUMN source_event_id UUID,
    ADD COLUMN source_event_sequence BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_wrk_items_source_event_sequence
        CHECK (source_event_sequence >= 0);

CREATE UNIQUE INDEX uk_wrk_items_source_projection
    ON wrk_items (tenant_id, source_system, source_reference)
    WHERE source_reference IS NOT NULL;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES (
    'PLATFORM.WORK_ITEM.WORK_TYPE', 'REVIEW', 'Review',
    '{"ko":"검토","en":"Review"}', 50,
    '{"authorityOwner":"dwp-auth-server","projectionOwner":"dwp-platform-server"}')
ON CONFLICT (code_set_key, code) DO UPDATE
   SET display_name = EXCLUDED.display_name,
       label_i18n = EXCLUDED.label_i18n,
       sort_order = EXCLUDED.sort_order,
       behavior_metadata = EXCLUDED.behavior_metadata;

COMMENT ON COLUMN wrk_items.source_event_sequence IS
    'Last applied owner aggregate sequence; rejects duplicate and out-of-order projections.';
