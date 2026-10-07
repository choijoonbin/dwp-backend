-- Target-population authority is provisioned by a dedicated feed identity.
-- Column grants intentionally exclude identifiers and every TIM command,
-- receipt, evidence, audit, rule-pack, and migration table.

DO $time_projection_publisher_access$
DECLARE
    publisher_role_name TEXT := '${timeProjectionPublisherRole}';
    runtime_role_name TEXT := '${timeRuntimeRole}';
    publisher_role RECORD;
BEGIN
    IF publisher_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid time projection publisher role name';
    END IF;
    IF publisher_role_name = runtime_role_name
       OR publisher_role_name = current_user
       OR publisher_role_name = session_user THEN
        RAISE EXCEPTION
            'Time projection publisher must be distinct from runtime and migration principals';
    END IF;

    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
      INTO publisher_role
      FROM pg_roles
     WHERE rolname = publisher_role_name;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Time projection publisher role % must exist', publisher_role_name;
    END IF;
    IF publisher_role.rolsuper
       OR publisher_role.rolcreaterole
       OR publisher_role.rolcreatedb
       OR publisher_role.rolreplication
       OR publisher_role.rolbypassrls THEN
        RAISE EXCEPTION
            'Time projection publisher role % has unsafe PostgreSQL attributes',
            publisher_role_name;
    END IF;

    EXECUTE format('REVOKE CREATE ON SCHEMA public FROM %I', publisher_role_name);
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', publisher_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE tim_target_population_projections TO %I',
        publisher_role_name);
    EXECUTE format(
        'GRANT UPDATE ('
        'projection_revision, lifecycle_state, effective_from, effective_to, '
        'source_digest, updated_at, updated_by) '
        'ON TABLE tim_target_population_projections TO %I',
        publisher_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE tim_target_population_actor_grants TO %I',
        publisher_role_name);
    EXECUTE format(
        'GRANT UPDATE ('
        'population_public_id, population_revision, grant_revision, lifecycle_state, '
        'valid_from, valid_to, source_digest, updated_at, updated_by) '
        'ON TABLE tim_target_population_actor_grants TO %I',
        publisher_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE tim_target_population_members TO %I',
        publisher_role_name);
    EXECUTE format(
        'GRANT UPDATE ('
        'population_revision, people_assignment_revision, membership_revision, '
        'lifecycle_state, effective_from, effective_to, source_digest, updated_at, updated_by) '
        'ON TABLE tim_target_population_members TO %I',
        publisher_role_name);
END
$time_projection_publisher_access$;

COMMENT ON TABLE tim_target_population_projections IS
    'TIM-owned target-population authority written only by a distinct least-privilege feed principal and read by the TIM runtime.';
