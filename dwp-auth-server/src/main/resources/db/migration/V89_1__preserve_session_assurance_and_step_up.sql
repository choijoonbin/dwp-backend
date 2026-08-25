ALTER TABLE sys_auth_sessions
    ADD COLUMN authentication_method VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN authenticated_at TIMESTAMPTZ,
    ADD COLUMN assurance_acr VARCHAR(200),
    ADD COLUMN assurance_amr JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE sys_auth_sessions
    ADD CONSTRAINT ck_sys_auth_sessions_assurance_amr_array
        CHECK (jsonb_typeof(assurance_amr) = 'array'),
    ADD CONSTRAINT ck_sys_auth_sessions_assurance_complete
        CHECK ((authenticated_at IS NULL AND assurance_acr IS NULL)
            OR (authenticated_at IS NOT NULL AND assurance_acr IS NOT NULL));

ALTER TABLE sys_identity_providers
    ADD COLUMN step_up_acr_values VARCHAR(500),
    ADD COLUMN step_up_max_age_seconds INTEGER NOT NULL DEFAULT 600;

ALTER TABLE sys_identity_providers
    ADD CONSTRAINT ck_sys_identity_providers_step_up_max_age
        CHECK (step_up_max_age_seconds BETWEEN 60 AND 3600);

COMMENT ON COLUMN sys_auth_sessions.authenticated_at IS
    'Immutable original authentication instant; never derived from token rotation issued_at.';
COMMENT ON COLUMN sys_auth_sessions.assurance_amr IS
    'Verified authentication methods copied from the identity provider or local login ceremony.';
COMMENT ON COLUMN sys_identity_providers.step_up_acr_values IS
    'Tenant/provider-controlled OIDC acr_values sent only by the Auth server.';
