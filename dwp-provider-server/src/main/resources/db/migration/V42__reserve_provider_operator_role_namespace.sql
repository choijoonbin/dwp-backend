-- Provider service roles are a second enforcement source and must use the
-- same reserved namespace as Auth, Gateway, and the shared role-plane policy.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM prv_operator_roles
         WHERE role_code <> UPPER(BTRIM(role_code))
            OR role_code !~ '^PROVIDER_[A-Z0-9_]+$') THEN
        RAISE EXCEPTION 'Provider operator role namespace contains an invalid role';
    END IF;
END;
$$;

ALTER TABLE prv_operator_roles
    ADD CONSTRAINT ck_prv_operator_roles_provider_namespace
        CHECK (role_code = UPPER(BTRIM(role_code))
               AND role_code ~ '^PROVIDER_[A-Z0-9_]+$');

COMMENT ON CONSTRAINT ck_prv_operator_roles_provider_namespace
    ON prv_operator_roles IS
    'Keeps provider-service role claims inside the shared PROVIDER_* control-plane namespace.';
