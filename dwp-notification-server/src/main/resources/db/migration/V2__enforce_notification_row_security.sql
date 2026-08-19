DO $role_setup$
DECLARE
    application_user NAME := session_user;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dwp_notification_api') THEN
        CREATE ROLE dwp_notification_api
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dwp_notification_worker') THEN
        CREATE ROLE dwp_notification_worker
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;

    ALTER ROLE dwp_notification_api
        NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    ALTER ROLE dwp_notification_worker
        NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

    IF application_user NOT IN ('dwp_notification_api', 'dwp_notification_worker') THEN
        EXECUTE format(
            'GRANT dwp_notification_api, dwp_notification_worker TO %I',
            application_user
        );
    END IF;
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE EXCEPTION
            'Notification runtime roles must be provisioned and granted to % before Flyway runs',
            application_user;
END
$role_setup$;

CREATE OR REPLACE FUNCTION ntf_current_tenant_id()
RETURNS BIGINT
LANGUAGE sql
STABLE
AS $$
    SELECT NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
$$;

CREATE OR REPLACE FUNCTION ntf_current_user_id()
RETURNS BIGINT
LANGUAGE sql
STABLE
AS $$
    SELECT NULLIF(current_setting('dwp.user_id', TRUE), '')::BIGINT
$$;

CREATE OR REPLACE FUNCTION ntf_is_runtime_role(role_name TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog
AS $$
DECLARE
    target_role OID;
BEGIN
    target_role := to_regrole(role_name);
    RETURN target_role IS NOT NULL
       AND current_user = role_name
       AND COALESCE(pg_has_role(session_user::regrole, target_role, 'MEMBER'), FALSE);
END
$$;

CREATE OR REPLACE FUNCTION ntf_is_api()
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT COALESCE(current_setting('dwp.notification_scope', TRUE), '') = 'API'
       AND ntf_is_runtime_role('dwp_notification_api')
$$;

CREATE OR REPLACE FUNCTION ntf_is_worker()
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT COALESCE(current_setting('dwp.notification_scope', TRUE), '') = 'WORKER'
       AND ntf_is_runtime_role('dwp_notification_worker')
$$;

ALTER TABLE ntf_notification_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_notification_types FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_type_scope ON ntf_notification_types
    USING (
        (ntf_is_api() OR ntf_is_worker())
        AND (tenant_id IS NULL OR tenant_id = ntf_current_tenant_id())
    )
    WITH CHECK (
        ntf_is_worker()
        AND (tenant_id IS NULL OR tenant_id = ntf_current_tenant_id())
    );

ALTER TABLE ntf_notification_type_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_notification_type_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_type_version_scope ON ntf_notification_type_versions
    USING (
        (ntf_is_api() OR ntf_is_worker())
        AND (tenant_id IS NULL OR tenant_id = ntf_current_tenant_id())
    )
    WITH CHECK (
        ntf_is_worker()
        AND (tenant_id IS NULL OR tenant_id = ntf_current_tenant_id())
    );

ALTER TABLE ntf_template_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_template_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_template_scope ON ntf_template_versions
    USING (
        (ntf_is_api() OR ntf_is_worker())
        AND (tenant_id IS NULL OR tenant_id = ntf_current_tenant_id())
    )
    WITH CHECK (
        ntf_is_worker()
        AND (tenant_id IS NULL OR tenant_id = ntf_current_tenant_id())
    );

ALTER TABLE ntf_routing_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_routing_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_policy_scope ON ntf_routing_policies
    USING (
        (ntf_is_api() OR ntf_is_worker())
        AND (tenant_id IS NULL OR tenant_id = ntf_current_tenant_id())
    )
    WITH CHECK (
        ntf_is_worker()
        AND (tenant_id IS NULL OR tenant_id = ntf_current_tenant_id())
    );

ALTER TABLE ntf_policy_channel_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_policy_channel_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_policy_channel_scope ON ntf_policy_channel_rules
    USING (
        (ntf_is_api() OR ntf_is_worker())
        AND (tenant_id IS NULL OR tenant_id = ntf_current_tenant_id())
    )
    WITH CHECK (
        ntf_is_worker()
        AND (tenant_id IS NULL OR tenant_id = ntf_current_tenant_id())
    );

ALTER TABLE ntf_notification_intents ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_notification_intents FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_intent_worker_scope ON ntf_notification_intents
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());

ALTER TABLE ntf_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_notifications FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_notification_scope ON ntf_notifications
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (
                ntf_is_api()
                AND EXISTS (
                    SELECT 1
                      FROM ntf_user_notifications user_notification
                     WHERE user_notification.tenant_id = ntf_notifications.tenant_id
                       AND user_notification.notification_id =
                           ntf_notifications.notification_id
                       AND user_notification.user_id = ntf_current_user_id()
                )
            )
        )
    )
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());

ALTER TABLE ntf_user_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_notifications FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_user_notification_scope ON ntf_user_notifications
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    )
    WITH CHECK (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    );

ALTER TABLE ntf_user_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_user_counter_scope ON ntf_user_counters
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    )
    WITH CHECK (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    );

ALTER TABLE ntf_user_delivery_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_delivery_profiles FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_delivery_profile_scope ON ntf_user_delivery_profiles
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    )
    WITH CHECK (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    );

ALTER TABLE ntf_user_subscription_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_subscription_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_subscription_rule_scope ON ntf_user_subscription_rules
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    )
    WITH CHECK (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    );

ALTER TABLE ntf_user_subscription_rule_channels ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_subscription_rule_channels FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_subscription_channel_scope ON ntf_user_subscription_rule_channels
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    )
    WITH CHECK (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    );

ALTER TABLE ntf_delivery_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_delivery_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_delivery_job_worker_scope ON ntf_delivery_jobs
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());

ALTER TABLE ntf_outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_outbox_events FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_outbox_worker_scope ON ntf_outbox_events
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());
CREATE POLICY ntf_outbox_api_insert_scope ON ntf_outbox_events
    FOR INSERT
    WITH CHECK (ntf_is_api() AND tenant_id = ntf_current_tenant_id());

ALTER TABLE ntf_idempotency_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_idempotency_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_idempotency_scope ON ntf_idempotency_receipts
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    )
    WITH CHECK (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    );

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON FUNCTION ntf_current_tenant_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION ntf_current_user_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION ntf_is_runtime_role(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION ntf_is_api() FROM PUBLIC;
REVOKE ALL ON FUNCTION ntf_is_worker() FROM PUBLIC;

GRANT USAGE ON SCHEMA public TO dwp_notification_api, dwp_notification_worker;
GRANT EXECUTE ON FUNCTION ntf_current_tenant_id(), ntf_current_user_id(),
    ntf_is_runtime_role(TEXT), ntf_is_api(), ntf_is_worker()
    TO dwp_notification_api, dwp_notification_worker;

GRANT SELECT ON ntf_notification_types, ntf_notification_type_versions,
    ntf_template_versions, ntf_routing_policies, ntf_policy_channel_rules,
    ntf_notifications TO dwp_notification_api;
GRANT SELECT, INSERT, UPDATE, DELETE ON ntf_user_notifications, ntf_user_counters,
    ntf_user_delivery_profiles, ntf_user_subscription_rules,
    ntf_user_subscription_rule_channels, ntf_idempotency_receipts
    TO dwp_notification_api;
GRANT INSERT ON ntf_outbox_events TO dwp_notification_api;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public
    TO dwp_notification_worker;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public
    TO dwp_notification_worker;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO dwp_notification_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO dwp_notification_worker;
