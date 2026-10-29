CREATE INDEX ix_msg_people_directory_page
    ON msg_people_snapshot (tenant_id, display_name, user_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX ix_msg_people_job_title_search
    ON msg_people_snapshot USING GIN (lower(job_title) gin_trgm_ops)
    WHERE lifecycle_state = 'ACTIVE' AND job_title IS NOT NULL;

CREATE INDEX ix_msg_people_organization_search
    ON msg_people_snapshot USING GIN (lower(organization_name) gin_trgm_ops)
    WHERE lifecycle_state = 'ACTIVE' AND organization_name IS NOT NULL;

COMMENT ON INDEX ix_msg_people_directory_page IS
    'Tenant-scoped deterministic keyset traversal for the active Messaging people directory.';
