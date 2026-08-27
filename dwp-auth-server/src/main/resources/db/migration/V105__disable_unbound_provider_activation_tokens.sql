-- Provider operators previously received the raw bearer capability used to
-- activate a tenant administrator. That made invitation issuance equivalent
-- to a customer administrator password reset. Revoke every outstanding token
-- and block new issuance until a customer-owned out-of-band delivery channel
-- is introduced by a later, explicit migration.

UPDATE sys_account_activation_tokens
   SET lifecycle_state = 'REVOKED',
       revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP)
 WHERE lifecycle_state = 'ACTIVE';

CREATE OR REPLACE FUNCTION sys_block_unbound_account_activation_token_issuance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'Account activation token issuance is disabled until customer-owned out-of-band delivery is configured'
        USING ERRCODE = '42501';
END;
$$;

CREATE TRIGGER trg_block_unbound_account_activation_token_issuance
BEFORE INSERT ON sys_account_activation_tokens
FOR EACH ROW EXECUTE FUNCTION sys_block_unbound_account_activation_token_issuance();

COMMENT ON TRIGGER trg_block_unbound_account_activation_token_issuance
    ON sys_account_activation_tokens IS
    'Release gate: prevents provider-visible administrator activation capabilities.';
