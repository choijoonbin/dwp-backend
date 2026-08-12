CREATE OR REPLACE FUNCTION sys_reject_closed_audit_case_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION 'Closed audit cases are immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_audit_cases_closed_immutable
BEFORE UPDATE OR DELETE ON sys_audit_cases
FOR EACH ROW EXECUTE FUNCTION sys_reject_closed_audit_case_update();

CREATE OR REPLACE FUNCTION sys_reject_closed_audit_case_child_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_case_id UUID;
    target_case_status VARCHAR(24);
BEGIN
    target_case_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.case_id ELSE NEW.case_id END;

    SELECT status
      INTO target_case_status
      FROM sys_audit_cases
     WHERE case_id = target_case_id;

    IF target_case_status = 'CLOSED' THEN
        RAISE EXCEPTION 'Closed audit case workspaces are immutable';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_audit_case_events_closed_immutable
BEFORE INSERT OR UPDATE OR DELETE ON sys_audit_case_events
FOR EACH ROW EXECUTE FUNCTION sys_reject_closed_audit_case_child_mutation();

CREATE TRIGGER trg_sys_audit_case_entities_closed_immutable
BEFORE INSERT OR UPDATE OR DELETE ON sys_audit_case_entities
FOR EACH ROW EXECUTE FUNCTION sys_reject_closed_audit_case_child_mutation();

CREATE TRIGGER trg_sys_audit_case_tasks_closed_immutable
BEFORE INSERT OR UPDATE OR DELETE ON sys_audit_case_tasks
FOR EACH ROW EXECUTE FUNCTION sys_reject_closed_audit_case_child_mutation();

CREATE OR REPLACE FUNCTION sys_reject_closed_audit_finding_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    linked_case_id UUID;
BEGIN
    FOR linked_case_id IN
        SELECT DISTINCT candidate.case_id
          FROM (VALUES (OLD.case_id), (NEW.case_id)) AS candidate(case_id)
         WHERE candidate.case_id IS NOT NULL
    LOOP
        IF EXISTS (
            SELECT 1
              FROM sys_audit_cases
             WHERE case_id = linked_case_id AND status = 'CLOSED'
        ) THEN
            RAISE EXCEPTION 'Findings linked to closed audit cases are immutable';
        END IF;
    END LOOP;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_audit_findings_closed_case_immutable
BEFORE UPDATE ON sys_audit_findings
FOR EACH ROW EXECUTE FUNCTION sys_reject_closed_audit_finding_mutation();

CREATE OR REPLACE FUNCTION sys_guard_closed_audit_case_activity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM sys_audit_cases
         WHERE case_id = NEW.case_id AND status = 'CLOSED'
    ) AND NOT (
        NEW.activity_type = 'STATUS_CHANGED'
        AND NEW.payload ->> 'toStatus' = 'CLOSED'
    ) THEN
        RAISE EXCEPTION 'Closed audit case timelines are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_audit_case_activities_closed_immutable
BEFORE INSERT ON sys_audit_case_activities
FOR EACH ROW EXECUTE FUNCTION sys_guard_closed_audit_case_activity();

COMMENT ON FUNCTION sys_reject_closed_audit_case_update() IS
    'Prevents reopening or editing an audit case after its immutable closure snapshot is established.';
