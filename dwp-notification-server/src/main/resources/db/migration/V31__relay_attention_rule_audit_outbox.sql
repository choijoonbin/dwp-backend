ALTER TABLE ntf_attention_rule_audit_outbox
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN lease_owner VARCHAR(255),
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN last_error VARCHAR(1000),
    ADD COLUMN dead_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT ck_ntf_attention_audit_attempts CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_ntf_attention_audit_lease CHECK (
        (lease_owner IS NULL) = (lease_until IS NULL)
    ),
    ADD CONSTRAINT ck_ntf_attention_audit_terminal CHECK (
        published_at IS NULL OR dead_at IS NULL
    );

DROP INDEX ix_ntf_attention_audit_unpublished;
CREATE INDEX ix_ntf_attention_audit_due
    ON ntf_attention_rule_audit_outbox (
        tenant_id, available_at, occurred_at, event_id
    )
    WHERE published_at IS NULL AND dead_at IS NULL;
CREATE INDEX ix_ntf_attention_audit_dead
    ON ntf_attention_rule_audit_outbox (tenant_id, dead_at, event_id)
    WHERE dead_at IS NOT NULL;
CREATE INDEX ix_ntf_attention_audit_published
    ON ntf_attention_rule_audit_outbox (tenant_id, published_at, event_id)
    WHERE published_at IS NOT NULL;

CREATE OR REPLACE FUNCTION ntf_guard_attention_audit_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF current_user <> 'dwp_notification_attention_audit_retention'
           OR OLD.published_at IS NULL
           OR OLD.published_at >= CURRENT_TIMESTAMP - INTERVAL '1 day' THEN
            RAISE EXCEPTION 'Attention audit evidence is append-only';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.event_id IS DISTINCT FROM OLD.event_id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.subject_type IS DISTINCT FROM OLD.subject_type
       OR NEW.subject_id IS DISTINCT FROM OLD.subject_id
       OR NEW.event_type IS DISTINCT FROM OLD.event_type
       OR NEW.scope_kind IS DISTINCT FROM OLD.scope_kind
       OR NEW.effect IS DISTINCT FROM OLD.effect
       OR NEW.subject_version IS DISTINCT FROM OLD.subject_version
       OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at THEN
        RAISE EXCEPTION 'Attention audit evidence is immutable';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count
       OR (OLD.published_at IS NOT NULL
           AND NEW.published_at IS DISTINCT FROM OLD.published_at)
       OR (OLD.dead_at IS NOT NULL AND NEW.dead_at IS DISTINCT FROM OLD.dead_at) THEN
        RAISE EXCEPTION 'Attention audit delivery state cannot move backwards';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_ntf_attention_audit_outbox_guard
BEFORE UPDATE OR DELETE ON ntf_attention_rule_audit_outbox
FOR EACH ROW EXECUTE FUNCTION ntf_guard_attention_audit_outbox();

DO $attention_audit_roles$
DECLARE
    application_user NAME := session_user;
    runtime_role_name TEXT := '${notificationRuntimeRole}';
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid notification runtime role name';
    END IF;
    IF to_regrole(runtime_role_name) IS NULL THEN
        RAISE EXCEPTION 'Notification runtime role % must exist', runtime_role_name;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles
         WHERE rolname = 'dwp_notification_attention_audit_relay'
    ) THEN
        CREATE ROLE dwp_notification_attention_audit_relay
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles
         WHERE rolname = 'dwp_notification_attention_audit_retention'
    ) THEN
        CREATE ROLE dwp_notification_attention_audit_retention
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    ALTER ROLE dwp_notification_attention_audit_relay
        NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    ALTER ROLE dwp_notification_attention_audit_retention
        NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    EXECUTE format(
        'GRANT dwp_notification_attention_audit_relay TO %I',
        runtime_role_name
    );
    IF application_user NOT IN (
        'dwp_notification_api',
        'dwp_notification_worker',
        'dwp_notification_audit_relay',
        'dwp_notification_attention_audit_relay',
        'dwp_notification_attention_audit_retention'
    ) THEN
        EXECUTE format(
            'GRANT dwp_notification_attention_audit_relay TO %I',
            application_user
        );
    END IF;
END
$attention_audit_roles$;

REVOKE ALL ON TABLE ntf_attention_rule_audit_outbox FROM PUBLIC;
REVOKE ALL ON TABLE ntf_attention_rule_audit_outbox FROM dwp_notification_worker;
REVOKE SELECT, UPDATE, DELETE ON TABLE ntf_attention_rule_audit_outbox
    FROM dwp_notification_api;
GRANT INSERT ON TABLE ntf_attention_rule_audit_outbox TO dwp_notification_api;
GRANT USAGE ON SCHEMA public
    TO dwp_notification_attention_audit_relay,
       dwp_notification_attention_audit_retention;
GRANT EXECUTE ON FUNCTION ntf_current_tenant_id()
    TO dwp_notification_attention_audit_relay,
       dwp_notification_attention_audit_retention;

DROP POLICY IF EXISTS ntf_attention_audit_worker_scope
    ON ntf_attention_rule_audit_outbox;
DROP POLICY IF EXISTS ntf_attention_audit_relay_select_scope
    ON ntf_attention_rule_audit_outbox;
DROP POLICY IF EXISTS ntf_attention_audit_relay_update_scope
    ON ntf_attention_rule_audit_outbox;
DROP POLICY IF EXISTS ntf_attention_audit_retention_select_scope
    ON ntf_attention_rule_audit_outbox;
DROP POLICY IF EXISTS ntf_attention_audit_retention_delete_scope
    ON ntf_attention_rule_audit_outbox;

CREATE POLICY ntf_attention_audit_relay_select_scope
    ON ntf_attention_rule_audit_outbox
    FOR SELECT TO dwp_notification_attention_audit_relay
    USING (
        current_user = 'dwp_notification_attention_audit_relay'
        AND tenant_id = ntf_current_tenant_id()
    );
CREATE POLICY ntf_attention_audit_relay_update_scope
    ON ntf_attention_rule_audit_outbox
    FOR UPDATE TO dwp_notification_attention_audit_relay
    USING (
        current_user = 'dwp_notification_attention_audit_relay'
        AND tenant_id = ntf_current_tenant_id()
    )
    WITH CHECK (
        current_user = 'dwp_notification_attention_audit_relay'
        AND tenant_id = ntf_current_tenant_id()
    );
CREATE POLICY ntf_attention_audit_retention_select_scope
    ON ntf_attention_rule_audit_outbox
    FOR SELECT TO dwp_notification_attention_audit_retention
    USING (
        current_user = 'dwp_notification_attention_audit_retention'
        AND tenant_id = ntf_current_tenant_id()
        AND published_at IS NOT NULL
        AND published_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
    );
CREATE POLICY ntf_attention_audit_retention_delete_scope
    ON ntf_attention_rule_audit_outbox
    FOR DELETE TO dwp_notification_attention_audit_retention
    USING (
        current_user = 'dwp_notification_attention_audit_retention'
        AND tenant_id = ntf_current_tenant_id()
        AND published_at IS NOT NULL
        AND published_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
    );

GRANT SELECT ON TABLE ntf_attention_rule_audit_outbox
    TO dwp_notification_attention_audit_relay;
GRANT UPDATE (
    attempt_count, available_at, lease_owner, lease_until,
    last_error, dead_at, published_at, updated_at
) ON TABLE ntf_attention_rule_audit_outbox
    TO dwp_notification_attention_audit_relay;
GRANT SELECT, DELETE ON TABLE ntf_attention_rule_audit_outbox
    TO dwp_notification_attention_audit_retention;

CREATE OR REPLACE FUNCTION ntf_purge_published_attention_audit_outbox(
    requested_tenant_id BIGINT,
    requested_cutoff TIMESTAMPTZ,
    requested_limit INTEGER
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    removed INTEGER;
BEGIN
    IF requested_tenant_id <> ntf_current_tenant_id()
       OR requested_cutoff IS NULL
       OR requested_cutoff > CURRENT_TIMESTAMP - INTERVAL '1 day'
       OR requested_limit < 1
       OR requested_limit > 500 THEN
        RAISE EXCEPTION 'Invalid attention audit retention request';
    END IF;
    DELETE FROM public.ntf_attention_rule_audit_outbox target
     WHERE target.event_id IN (
        SELECT candidate.event_id
          FROM public.ntf_attention_rule_audit_outbox candidate
         WHERE candidate.tenant_id = requested_tenant_id
           AND candidate.published_at IS NOT NULL
           AND candidate.published_at < requested_cutoff
         ORDER BY candidate.published_at, candidate.event_id
         LIMIT requested_limit
     );
    GET DIAGNOSTICS removed = ROW_COUNT;
    RETURN removed;
END
$$;

ALTER FUNCTION ntf_purge_published_attention_audit_outbox(
    BIGINT, TIMESTAMPTZ, INTEGER
) OWNER TO dwp_notification_attention_audit_retention;
REVOKE ALL ON FUNCTION ntf_purge_published_attention_audit_outbox(
    BIGINT, TIMESTAMPTZ, INTEGER
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ntf_purge_published_attention_audit_outbox(
    BIGINT, TIMESTAMPTZ, INTEGER
) TO dwp_notification_attention_audit_relay;

REVOKE ALL ON FUNCTION ntf_guard_attention_audit_outbox() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ntf_guard_attention_audit_outbox()
    TO dwp_notification_api,
       dwp_notification_attention_audit_relay,
       dwp_notification_attention_audit_retention;

COMMENT ON COLUMN ntf_attention_rule_audit_outbox.available_at IS
    'Next eligible relay time after bounded exponential backoff.';
COMMENT ON COLUMN ntf_attention_rule_audit_outbox.dead_at IS
    'Poison evidence retained for operator remediation; never silently discarded.';
COMMENT ON FUNCTION ntf_purge_published_attention_audit_outbox(
    BIGINT, TIMESTAMPTZ, INTEGER
) IS
    'Bounded retention for facts already accepted by the canonical audit store.';
