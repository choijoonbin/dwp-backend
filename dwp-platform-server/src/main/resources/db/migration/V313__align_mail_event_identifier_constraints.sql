ALTER TABLE mail_audit_events
    DROP CONSTRAINT ck_mail_audit_action,
    ADD CONSTRAINT ck_mail_audit_action CHECK (
        action ~ '^mail(\.[a-z][a-z0-9-]*){2,15}$');

ALTER TABLE mail_domain_events
    DROP CONSTRAINT ck_mail_domain_event_type,
    ADD CONSTRAINT ck_mail_domain_event_type CHECK (
        event_type ~ '^mail(\.[a-z][a-z0-9-]*){2,15}$');

COMMENT ON CONSTRAINT ck_mail_audit_action ON mail_audit_events IS
    'Uses the canonical domain-event identifier grammar; hyphens are permitted inside a namespace segment.';
COMMENT ON CONSTRAINT ck_mail_domain_event_type ON mail_domain_events IS
    'Uses the canonical domain-event identifier grammar; hyphens are permitted inside a namespace segment.';
