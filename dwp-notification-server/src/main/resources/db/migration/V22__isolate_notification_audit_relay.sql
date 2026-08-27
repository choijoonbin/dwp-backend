DO $audit_relay_role$
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
        SELECT 1 FROM pg_roles WHERE rolname = 'dwp_notification_audit_relay'
    ) THEN
        CREATE ROLE dwp_notification_audit_relay
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;

    ALTER ROLE dwp_notification_audit_relay
        NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

    EXECUTE format(
        'REVOKE ALL ON TABLE sys_audit_outbox FROM %I',
        runtime_role_name
    );
    EXECUTE format(
        'GRANT dwp_notification_audit_relay TO %I',
        runtime_role_name
    );

    IF application_user NOT IN (
        'dwp_notification_api',
        'dwp_notification_worker',
        'dwp_notification_audit_relay'
    ) THEN
        EXECUTE format(
            'GRANT dwp_notification_audit_relay TO %I',
            application_user
        );
    END IF;
END
$audit_relay_role$;

REVOKE ALL ON TABLE sys_audit_outbox FROM PUBLIC;
REVOKE ALL ON TABLE sys_audit_outbox FROM dwp_notification_api;
REVOKE ALL ON TABLE sys_audit_outbox FROM dwp_notification_worker;

ALTER TABLE sys_audit_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_audit_outbox FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS ntf_audit_outbox_api_select_scope ON sys_audit_outbox;
DROP POLICY IF EXISTS ntf_audit_outbox_api_insert_scope ON sys_audit_outbox;
DROP POLICY IF EXISTS ntf_audit_outbox_relay_scope ON sys_audit_outbox;

CREATE POLICY ntf_audit_outbox_api_select_scope ON sys_audit_outbox
    FOR SELECT TO dwp_notification_api
    USING (
        ntf_is_api()
        AND tenant_id = ntf_current_tenant_id()
    );

CREATE POLICY ntf_audit_outbox_api_insert_scope ON sys_audit_outbox
    FOR INSERT TO dwp_notification_api
    WITH CHECK (
        ntf_is_api()
        AND tenant_id = ntf_current_tenant_id()
    );

CREATE POLICY ntf_audit_outbox_relay_scope ON sys_audit_outbox
    TO dwp_notification_audit_relay
    USING (current_user = 'dwp_notification_audit_relay')
    WITH CHECK (current_user = 'dwp_notification_audit_relay');

GRANT INSERT ON TABLE sys_audit_outbox TO dwp_notification_api;
GRANT SELECT (event_id) ON TABLE sys_audit_outbox TO dwp_notification_api;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE sys_audit_outbox
    TO dwp_notification_audit_relay;

COMMENT ON POLICY ntf_audit_outbox_relay_scope ON sys_audit_outbox IS
    'The durable audit relay must explicitly assume its fenced database role; the runtime login has no direct table grant.';
