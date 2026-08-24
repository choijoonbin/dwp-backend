ALTER TABLE sys_identity_providers
    ADD COLUMN step_up_accepted_amr_values VARCHAR(500);

COMMENT ON COLUMN sys_identity_providers.step_up_accepted_amr_values IS
    'Closed space-delimited AMR allowlist for step-up; known values only and at least one strong MFA pattern. NULL is intentionally incompatible.';
