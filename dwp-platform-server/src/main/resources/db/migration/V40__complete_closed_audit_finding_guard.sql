DROP TRIGGER IF EXISTS trg_sys_audit_findings_closed_case_immutable ON sys_audit_findings;
DROP FUNCTION IF EXISTS sys_reject_closed_audit_finding_mutation();

CREATE OR REPLACE FUNCTION sys_reject_closed_audit_finding_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    linked_case_id UUID;
BEGIN
    IF TG_OP = 'INSERT' THEN
        linked_case_id := NEW.case_id;
    ELSIF TG_OP = 'DELETE' THEN
        linked_case_id := OLD.case_id;
    ELSE
        IF OLD.case_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM sys_audit_cases
             WHERE case_id = OLD.case_id AND status = 'CLOSED'
        ) THEN
            RAISE EXCEPTION 'Findings linked to closed audit cases are immutable';
        END IF;
        linked_case_id := NEW.case_id;
    END IF;

    IF linked_case_id IS NOT NULL THEN
        IF EXISTS (
            SELECT 1
              FROM sys_audit_cases
             WHERE case_id = linked_case_id AND status = 'CLOSED'
        ) THEN
            RAISE EXCEPTION 'Findings linked to closed audit cases are immutable';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_audit_findings_closed_case_immutable
BEFORE INSERT OR UPDATE OR DELETE ON sys_audit_findings
FOR EACH ROW EXECUTE FUNCTION sys_reject_closed_audit_finding_mutation();

COMMENT ON FUNCTION sys_reject_closed_audit_finding_mutation() IS
    'Prevents adding, changing, unlinking, or deleting findings in a closed audit case workspace.';
