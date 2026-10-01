ALTER TABLE sys_audit_policy_revisions
    ADD COLUMN impact_snapshot JSONB,
    ADD COLUMN impact_sha256 CHAR(64);

UPDATE sys_audit_policy_revisions
   SET impact_snapshot = jsonb_build_object(
           'observedAt', created_at,
           'coverageState', 'UNAVAILABLE_LEGACY_REVISION',
           'includedOwners', jsonb_build_array(),
           'exclusions', jsonb_build_array('LEGACY_REVISION_NOT_SNAPSHOTTED'),
           'auditEventCount', 0,
           'affectedAuditEventCount', 0,
           'affectedActorCount', 0,
           'affectedTargetCount', 0,
           'standardRetentionEventCount', 0,
           'extendedRetentionEventCount', 0,
           'legalHoldEventCount', 0,
           'standardRetentionAffectedEventCount', 0,
           'extendedRetentionAffectedEventCount', 0,
           'highRiskClassificationAffectedEventCount', 0,
           'exportableEventCountBefore', 0,
           'exportableEventCountAfter', 0,
           'integrityProtectedEventCountBefore', 0,
           'integrityProtectedEventCountAfter', 0)
 WHERE impact_snapshot IS NULL;

UPDATE sys_audit_policy_revisions
   SET impact_sha256 = encode(digest(impact_snapshot::TEXT, 'sha256'), 'hex')
 WHERE impact_sha256 IS NULL;

ALTER TABLE sys_audit_policy_revisions
    ALTER COLUMN impact_snapshot SET NOT NULL,
    ALTER COLUMN impact_sha256 SET NOT NULL,
    ADD CONSTRAINT ck_sys_audit_policy_revision_impact_snapshot
        CHECK (jsonb_typeof(impact_snapshot) = 'object'),
    ADD CONSTRAINT ck_sys_audit_policy_revision_impact_hash
        CHECK (impact_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_sys_audit_policy_revision_impact_binding
        CHECK (impact_sha256 = encode(digest(impact_snapshot::TEXT, 'sha256'), 'hex'));

CREATE OR REPLACE FUNCTION sys_reject_audit_policy_impact_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit policy impact evidence is immutable';
    END IF;
    IF NEW.impact_snapshot IS DISTINCT FROM OLD.impact_snapshot
       OR NEW.impact_sha256 IS DISTINCT FROM OLD.impact_sha256 THEN
        RAISE EXCEPTION 'audit policy impact evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sys_audit_policy_impact_immutable
    BEFORE UPDATE OF impact_snapshot, impact_sha256 ON sys_audit_policy_revisions
    FOR EACH ROW EXECUTE FUNCTION sys_reject_audit_policy_impact_mutation();

CREATE TRIGGER trg_sys_audit_policy_impact_delete_immutable
    BEFORE DELETE ON sys_audit_policy_revisions
    FOR EACH ROW EXECUTE FUNCTION sys_reject_audit_policy_impact_mutation();

COMMENT ON COLUMN sys_audit_policy_revisions.impact_snapshot IS
    'Immutable internal audit-event population impact captured when the revision was drafted.';
COMMENT ON COLUMN sys_audit_policy_revisions.impact_sha256 IS
    'SHA-256 evidence binding for the stored population impact snapshot.';
