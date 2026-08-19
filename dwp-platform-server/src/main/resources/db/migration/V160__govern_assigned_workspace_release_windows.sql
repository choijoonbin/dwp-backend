CREATE TABLE wp_resource_release_windows (
    release_window_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    resource_id UUID NOT NULL,
    released_by_user_id BIGINT NOT NULL,
    released_by_person_public_id UUID,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    note VARCHAR(240),
    release_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_wp_resource_release_windows_tenant_id
        UNIQUE (tenant_id, release_window_id),
    CONSTRAINT fk_wp_resource_release_windows_tenant_resource
        FOREIGN KEY (tenant_id, resource_id)
        REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT ck_wp_resource_release_windows_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_wp_resource_release_windows_status CHECK (
        release_status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_wp_resource_release_windows_cancelled CHECK (
        (release_status = 'ACTIVE' AND cancelled_at IS NULL)
        OR (release_status = 'CANCELLED' AND cancelled_at IS NOT NULL))
);

ALTER TABLE wp_resource_release_windows
    ADD CONSTRAINT ex_wp_resource_release_windows_overlap
    EXCLUDE USING gist (
        tenant_id WITH =,
        resource_id WITH =,
        (tstzrange(starts_at, ends_at, '[)')) WITH &&)
    WHERE (release_status = 'ACTIVE');

CREATE INDEX idx_wp_resource_release_windows_owner
    ON wp_resource_release_windows (
        tenant_id, released_by_user_id, starts_at, ends_at)
    WHERE release_status = 'ACTIVE';

CREATE INDEX idx_wp_resource_release_windows_booking
    ON wp_resource_release_windows (tenant_id, resource_id, starts_at, ends_at)
    WHERE release_status = 'ACTIVE';

COMMENT ON TABLE wp_resource_release_windows IS
    'Explicit owner-controlled lending windows for assigned desks and other assigned workspaces.';
COMMENT ON CONSTRAINT ex_wp_resource_release_windows_overlap
    ON wp_resource_release_windows IS
    'Prevents ambiguous overlapping release authority for one assigned resource.';
