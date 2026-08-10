UPDATE prv_tenant_administrators
SET email = CASE
        WHEN email = 'admin@localhost' THEN 'admin@dwp.local'
        WHEN email IS NOT NULL THEN LOWER(BTRIM(email))
        WHEN principal LIKE '%@%' THEN LOWER(BTRIM(principal))
        ELSE 'administrator-' || tenant_administrator_id || '@invalid.example'
    END,
    updated_at = CURRENT_TIMESTAMP;

ALTER TABLE prv_tenant_administrators
    DROP CONSTRAINT IF EXISTS uk_prv_tenant_administrators_principal;

ALTER TABLE prv_tenant_administrators
    ALTER COLUMN email SET NOT NULL,
    DROP COLUMN principal;

CREATE UNIQUE INDEX uk_prv_tenant_administrators_email
    ON prv_tenant_administrators(provider_tenant_id, LOWER(BTRIM(email)));
