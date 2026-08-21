-- Central audit owns the canonical policy decision vocabulary. Repair
-- unpublished notification audit events emitted before that boundary was
-- enforced so the outbox can be delivered without weakening the contract.
UPDATE sys_audit_outbox
   SET payload = jsonb_set(payload, '{policyDecision}', '"DENY"'::jsonb),
       updated_at = CURRENT_TIMESTAMP
 WHERE status IN ('PENDING', 'FAILED')
   AND payload ->> 'policyDecision' = 'DENY_BY_POLICY';
