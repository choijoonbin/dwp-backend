DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM com_role_hierarchy LIMIT 1) THEN
        RAISE EXCEPTION 'com_role_hierarchy contains data and requires an explicit migration plan';
    END IF;
    IF EXISTS (SELECT 1 FROM com_separation_of_duty_rules LIMIT 1) THEN
        RAISE EXCEPTION 'com_separation_of_duty_rules contains data and requires an explicit migration plan';
    END IF;
END
$$;

-- Effective access is computed from explicit assignments. Role conflict enforcement is
-- owned by sys_role_assignment_policies and sys_role_conflict_policies.
DROP TABLE com_role_hierarchy;
DROP TABLE com_separation_of_duty_rules;
