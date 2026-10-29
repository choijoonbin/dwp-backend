UPDATE msg_people_snapshot
   SET person_public_id = gen_random_uuid()
 WHERE person_public_id IS NULL;

ALTER TABLE msg_people_snapshot
    ALTER COLUMN person_public_id SET DEFAULT gen_random_uuid(),
    ALTER COLUMN person_public_id SET NOT NULL;

CREATE UNIQUE INDEX ux_msg_people_public_identity
    ON msg_people_snapshot (tenant_id, person_public_id);

CREATE INDEX ix_msg_people_directory_cursor_v2
    ON msg_people_snapshot (tenant_id, display_name, person_public_id)
    WHERE lifecycle_state = 'ACTIVE';

COMMENT ON INDEX ix_msg_people_directory_cursor_v2 IS
    'Tenant-scoped people keyset traversal using only the public UUID tie-breaker.';
