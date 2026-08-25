-- A composer undo is an explicit, auditable command. Persist the exact undo
-- revision so an Idempotency-Key can only replay the proposal it originally
-- restored, never an unrelated command owned by the same user.
ALTER TABLE usr_home_composer_proposals
    ADD COLUMN undone_revision_id UUID
        REFERENCES usr_home_view_revisions(revision_id);

-- Best-effort recovery for any pre-release UNDONE rows. The first UNDO after
-- the recorded apply revision is the only deterministic historical match.
UPDATE usr_home_composer_proposals proposal
   SET undone_revision_id = (
       SELECT undo_revision.revision_id
         FROM usr_home_view_revisions undo_revision
         JOIN usr_home_view_revisions applied_revision
           ON applied_revision.revision_id = proposal.applied_revision_id
        WHERE undo_revision.view_id = proposal.view_id
          AND undo_revision.source = 'UNDO'
          AND undo_revision.revision_number > applied_revision.revision_number
        ORDER BY undo_revision.revision_number
        LIMIT 1)
 WHERE proposal.state = 'UNDONE'
   AND proposal.undone_revision_id IS NULL;

CREATE UNIQUE INDEX uk_usr_home_composer_undone_revision
    ON usr_home_composer_proposals (undone_revision_id)
    WHERE undone_revision_id IS NOT NULL;

ALTER TABLE usr_home_composer_proposals
    ADD CONSTRAINT ck_usr_home_composer_undone_revision
        CHECK (state <> 'UNDONE' OR undone_revision_id IS NOT NULL) NOT VALID;
