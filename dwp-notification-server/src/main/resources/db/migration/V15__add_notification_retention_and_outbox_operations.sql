CREATE TABLE ntf_notification_retention_holds (
    hold_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    notification_id UUID NOT NULL,
    user_id BIGINT,
    case_reference VARCHAR(200) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    released_by BIGINT,
    release_reason VARCHAR(500),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ntf_retention_hold_notification
        FOREIGN KEY (tenant_id, notification_id)
        REFERENCES ntf_notifications (tenant_id, notification_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_ntf_retention_hold_range CHECK (
        expires_at IS NULL OR expires_at > starts_at
    ),
    CONSTRAINT ck_ntf_retention_hold_release CHECK (
        (released_at IS NULL AND released_by IS NULL AND release_reason IS NULL)
        OR (released_at IS NOT NULL AND released_by IS NOT NULL
            AND length(trim(release_reason)) > 0)
    )
);

CREATE INDEX ix_ntf_retention_hold_active
    ON ntf_notification_retention_holds
        (tenant_id, notification_id, user_id, starts_at, expires_at)
    WHERE released_at IS NULL;
CREATE INDEX ix_ntf_notification_expiry
    ON ntf_notifications (tenant_id, expires_at, notification_id)
    WHERE expires_at IS NOT NULL;

ALTER TABLE ntf_notification_retention_holds ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_notification_retention_holds FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_retention_hold_worker_scope ON ntf_notification_retention_holds
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON ntf_notification_retention_holds
    TO dwp_notification_worker;

ALTER TABLE ntf_outbox_events
    ADD COLUMN lease_owner VARCHAR(160),
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN last_error VARCHAR(1000),
    ADD COLUMN dead_at TIMESTAMPTZ;

DROP INDEX ix_ntf_outbox_due;
CREATE INDEX ix_ntf_outbox_due
    ON ntf_outbox_events (next_attempt_at, created_at)
    WHERE published_at IS NULL AND dead_at IS NULL;
CREATE INDEX ix_ntf_outbox_published_cleanup
    ON ntf_outbox_events (tenant_id, published_at)
    WHERE published_at IS NOT NULL;

CREATE TABLE ntf_runtime_tenants (
    tenant_id BIGINT PRIMARY KEY,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO ntf_runtime_tenants (tenant_id, first_seen_at, last_seen_at)
SELECT tenant_id, MIN(created_at), MAX(created_at)
  FROM ntf_outbox_events
 GROUP BY tenant_id
ON CONFLICT (tenant_id) DO UPDATE
    SET last_seen_at = GREATEST(
        ntf_runtime_tenants.last_seen_at, EXCLUDED.last_seen_at);

CREATE OR REPLACE FUNCTION ntf_register_runtime_tenant()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF NEW.tenant_id IS NULL
        OR ntf_current_tenant_id() IS NULL
        OR NEW.tenant_id <> ntf_current_tenant_id() THEN
        RAISE EXCEPTION 'Notification runtime tenant scope is invalid';
    END IF;
    INSERT INTO ntf_runtime_tenants (tenant_id)
    VALUES (NEW.tenant_id)
    ON CONFLICT (tenant_id) DO UPDATE
        SET last_seen_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION ntf_register_runtime_tenant() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ntf_register_runtime_tenant()
    TO dwp_notification_api, dwp_notification_worker;

CREATE TRIGGER trg_ntf_outbox_register_runtime_tenant
AFTER INSERT ON ntf_outbox_events
FOR EACH ROW EXECUTE FUNCTION ntf_register_runtime_tenant();

GRANT SELECT ON ntf_runtime_tenants TO dwp_notification_worker;

COMMENT ON TABLE ntf_notification_retention_holds IS
    'Legal-hold extension point. Active holds block retention purge for a notification or user projection.';
COMMENT ON COLUMN ntf_notifications.expires_at IS
    'Purge eligibility instant. Saved projections and active retention holds remain protected.';
COMMENT ON TABLE ntf_runtime_tenants IS
    'Content-free registry used by tenant-scoped maintenance schedulers.';
COMMENT ON COLUMN ntf_outbox_events.dead_at IS
    'Set after the configured retry budget; dead events are retained for operator recovery.';
