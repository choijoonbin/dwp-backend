CREATE TABLE wp_experience_facility_retention_counters (
    tenant_id BIGINT PRIMARY KEY REFERENCES wp_tenant_policies(tenant_id),
    requests_purged BIGINT NOT NULL DEFAULT 0 CHECK (requests_purged >= 0),
    closures_purged BIGINT NOT NULL DEFAULT 0 CHECK (closures_purged >= 0),
    last_purged_at TIMESTAMPTZ
);

CREATE FUNCTION wp_facility_audit_retention_protected(tenant BIGINT, kind VARCHAR, target UUID)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM wp_audit_events a
        WHERE a.tenant_id = tenant AND a.aggregate_type = kind AND a.aggregate_id = target
          AND (EXISTS(SELECT 1 FROM sys_audit_outbox o
                WHERE o.tenant_id = tenant AND o.event_id = a.audit_event_id AND o.status = 'SENDING')
            OR EXISTS(SELECT 1 FROM sys_audit_events e
                WHERE e.tenant_id = tenant AND e.event_id = a.audit_event_id AND e.retention_class = 'LEGAL_HOLD')
            OR EXISTS(SELECT 1 FROM sys_audit_case_events c JOIN sys_audit_cases k ON k.case_id = c.case_id
                WHERE k.tenant_id = tenant AND c.event_id = a.audit_event_id)
            OR EXISTS(SELECT 1 FROM sys_audit_findings f WHERE f.tenant_id = tenant
                AND f.event_id = a.audit_event_id AND f.status NOT IN ('RESOLVED','DISMISSED'))
            OR EXISTS(SELECT 1 FROM wrk_activity_events w
                WHERE w.tenant_id = tenant AND w.audit_record_id = a.audit_event_id))
    );
$$;

CREATE VIEW wp_experience_facility_retention_eligible_requests AS
SELECT x.request_id, x.tenant_id FROM wp_experience_facility_requests x
JOIN wp_tenant_policies p ON p.tenant_id = x.tenant_id
WHERE x.request_status IN ('RESOLVED','CANCELLED')
  AND x.updated_at + make_interval(days => p.booking_retention_days) < CURRENT_TIMESTAMP
  AND NOT wp_facility_audit_retention_protected(x.tenant_id, 'FACILITY_REQUEST', x.request_id);

CREATE VIEW wp_experience_facility_retention_eligible_closures AS
SELECT x.closure_id, x.tenant_id FROM wp_experience_facility_closures x
JOIN wp_tenant_policies p ON p.tenant_id = x.tenant_id
WHERE GREATEST(x.ends_at, x.updated_at) + make_interval(days => p.booking_retention_days) < CURRENT_TIMESTAMP
  AND NOT EXISTS(SELECT 1 FROM wp_bookings b WHERE b.tenant_id = x.tenant_id
      AND b.resource_id = x.resource_id AND b.legal_hold
      AND b.starts_at < x.ends_at AND b.ends_at > x.starts_at)
  AND NOT wp_facility_audit_retention_protected(x.tenant_id, 'FACILITY_CLOSURE', x.closure_id);

-- Audit remains append-only except this exact facility retention redaction.
-- Neither action, event identity, target nor occurrence time can be rewritten.
CREATE OR REPLACE FUNCTION wp_reject_audit_event_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('dwp.audit_retention_bypass', TRUE) = 'on' THEN RETURN OLD; END IF;
    IF TG_OP = 'UPDATE' AND current_setting('dwp.facility_retention_redaction', TRUE) = 'on'
       AND OLD.aggregate_type IN ('FACILITY_REQUEST','FACILITY_CLOSURE')
       AND OLD.action IN ('workplace.facility.request_created','workplace.facility.request_status_changed',
           'workplace.facility.closure_created','workplace.facility.closure_cancelled')
       AND (to_jsonb(NEW) - 'snapshot' - 'actor_user_id' - 'correlation_id')
           = (to_jsonb(OLD) - 'snapshot' - 'actor_user_id' - 'correlation_id')
       AND NEW.actor_user_id = 0 AND NEW.correlation_id IS NULL
       AND NEW.snapshot = '{"retentionAction":"FACILITY_PERSONAL_DATA_REDACTED"}'::JSONB THEN RETURN NEW;
    END IF;
    RAISE EXCEPTION 'wp_audit_events is append-only';
END;
$$;
