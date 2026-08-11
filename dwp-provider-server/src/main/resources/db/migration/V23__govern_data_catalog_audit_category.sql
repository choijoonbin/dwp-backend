UPDATE prv_audit_events
   SET event_category = CASE
       WHEN event_category IN ('PROVISIONING', 'ONBOARDING') THEN 'TENANT_LIFECYCLE'
       WHEN event_category IN ('SUPPORT', 'SECURITY') THEN 'PRIVILEGED_ACCESS'
       ELSE event_category
       END
 WHERE event_category IN ('PROVISIONING', 'ONBOARDING', 'SUPPORT', 'SECURITY');

ALTER TABLE prv_audit_events
    ADD CONSTRAINT ck_prv_audit_events_category
        CHECK (event_category IN (
            'ADMINISTRATION', 'PRIVILEGED_ACCESS', 'SERVICE_HEALTH',
            'CHANGE_MANAGEMENT', 'TENANT_LIFECYCLE', 'DATA_GOVERNANCE'));

COMMENT ON COLUMN prv_audit_events.event_category IS
    'Governed provider audit category registered by the global product code contract.';
