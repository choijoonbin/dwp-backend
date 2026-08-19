DO $runtime_identity$
DECLARE
    runtime_role_name TEXT := '${notificationRuntimeRole}';
    runtime_role RECORD;
    owned_objects BIGINT;
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid notification runtime role name';
    END IF;

    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
      INTO runtime_role
      FROM pg_roles
     WHERE rolname = runtime_role_name;
    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Notification runtime role % must be provisioned before Flyway runs',
            runtime_role_name;
    END IF;
    IF runtime_role.rolsuper
       OR runtime_role.rolcreaterole
       OR runtime_role.rolcreatedb
       OR runtime_role.rolreplication
       OR runtime_role.rolbypassrls THEN
        RAISE EXCEPTION
            'Notification runtime role % has unsafe PostgreSQL attributes',
            runtime_role_name;
    END IF;

    SELECT COUNT(*)
      INTO owned_objects
      FROM pg_class relation
      JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
      JOIN pg_roles owner_role ON owner_role.oid = relation.relowner
     WHERE namespace.nspname = 'public'
       AND owner_role.rolname = runtime_role_name;
    IF owned_objects > 0 THEN
        RAISE EXCEPTION
            'Notification runtime role % must not own application objects',
            runtime_role_name;
    END IF;

    EXECUTE format(
        'GRANT dwp_notification_api, dwp_notification_worker TO %I',
        runtime_role_name
    );
    EXECUTE format(
        'REVOKE CREATE ON SCHEMA public FROM %I',
        runtime_role_name
    );
    EXECUTE format(
        'REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I',
        runtime_role_name
    );
    EXECUTE format(
        'REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I',
        runtime_role_name
    );
END
$runtime_identity$;
