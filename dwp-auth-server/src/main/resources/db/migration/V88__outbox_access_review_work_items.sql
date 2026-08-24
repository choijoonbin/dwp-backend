-- Stable, opaque references are the only access-review identifiers exposed to Work.
-- The shared sys_domain_event_outbox ledger is supplied by dwp-core; this migration
-- adds the aggregate evidence needed to publish assigned/decided/revoked events.
ALTER TABLE com_access_review_items
    ADD COLUMN work_item_ref UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN reviewer_assignment_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN work_event_sequence BIGINT NOT NULL DEFAULT 0;

ALTER TABLE com_access_review_items
    ADD CONSTRAINT uk_access_review_item_work_ref
        UNIQUE (tenant_id, work_item_ref),
    ADD CONSTRAINT ck_access_review_item_reviewer_assignment
        CHECK (reviewer_assignment_state IN ('ACTIVE', 'REVOKED')),
    ADD CONSTRAINT ck_access_review_item_work_event_sequence
        CHECK (work_event_sequence >= 0);

CREATE INDEX idx_access_review_item_named_work
    ON com_access_review_items (
        tenant_id, reviewer_user_id, reviewer_assignment_state, work_item_ref)
    WHERE reviewer_user_id IS NOT NULL;

COMMENT ON COLUMN com_access_review_items.work_item_ref IS
    'Opaque reference exposed to the assigned-review Work surface; internal campaign/item ids remain private.';
COMMENT ON COLUMN com_access_review_items.reviewer_assignment_state IS
    'Revocable named-reviewer relationship evidence; never inferred from a role.';
COMMENT ON COLUMN com_access_review_items.work_event_sequence IS
    'Durable sequence for ACCESS_REVIEW_WORK_ITEM events; independent of the domain object version.';
