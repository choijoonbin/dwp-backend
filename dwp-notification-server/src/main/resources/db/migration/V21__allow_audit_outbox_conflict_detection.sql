-- PostgreSQL requires SELECT on the conflict target used by
-- INSERT ... ON CONFLICT. The API role only needs the opaque event id; audit
-- payloads and delivery state remain unavailable to request transactions.
GRANT SELECT (event_id)
    ON sys_audit_outbox
    TO dwp_notification_api;

COMMENT ON COLUMN sys_audit_outbox.event_id IS
    'Opaque audit id exposed to the API role only for idempotent conflict detection.';
