-- The projection feed is an authority plane of its own. It can build and seal
-- legal-entity memberships, but it cannot write payroll configurations,
-- receipts, audit facts, schema objects, or migration history.

DO $payroll_projection_publisher_access$
DECLARE
    publisher_role_name TEXT := '${payrollProjectionPublisherRole}';
    runtime_role_name TEXT := '${payrollRuntimeRole}';
    publisher_role RECORD;
BEGIN
    IF publisher_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid payroll projection publisher role name';
    END IF;
    IF publisher_role_name = runtime_role_name
       OR publisher_role_name = current_user
       OR publisher_role_name = session_user THEN
        RAISE EXCEPTION
            'Payroll projection publisher must be distinct from runtime and migration principals';
    END IF;

    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
      INTO publisher_role
      FROM pg_roles
     WHERE rolname = publisher_role_name;
    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Payroll projection publisher role % must exist', publisher_role_name;
    END IF;
    IF publisher_role.rolsuper
       OR publisher_role.rolcreaterole
       OR publisher_role.rolcreatedb
       OR publisher_role.rolreplication
       OR publisher_role.rolbypassrls THEN
        RAISE EXCEPTION
            'Payroll projection publisher role % has unsafe PostgreSQL attributes',
            publisher_role_name;
    END IF;

    EXECUTE format('REVOKE CREATE ON SCHEMA public FROM %I', publisher_role_name);
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', publisher_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE pay_legal_entity_scope_projections TO %I',
        publisher_role_name);
    EXECUTE format(
        'GRANT UPDATE (status, valid_until) '
        'ON TABLE pay_legal_entity_scope_projections TO %I',
        publisher_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE pay_legal_entity_scope_members TO %I',
        publisher_role_name);
END
$payroll_projection_publisher_access$;

COMMENT ON TABLE pay_legal_entity_scope_projections IS
    'Versioned Payroll-owned authority projection. A distinct least-privilege feed principal builds, seals, supersedes, or revokes revisions; the Payroll runtime remains read-only.';
