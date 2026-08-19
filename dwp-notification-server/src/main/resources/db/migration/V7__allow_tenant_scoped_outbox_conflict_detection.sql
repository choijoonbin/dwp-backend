GRANT SELECT (tenant_id, event_key)
    ON ntf_outbox_events
    TO dwp_notification_api;

CREATE POLICY ntf_outbox_api_conflict_scope ON ntf_outbox_events
    FOR SELECT
    USING (
        ntf_is_api()
        AND tenant_id = ntf_current_tenant_id()
    );

COMMENT ON POLICY ntf_outbox_api_conflict_scope ON ntf_outbox_events IS
    'Allows the API role to resolve only same-tenant outbox idempotency conflicts.';
