ALTER TABLE prv_tenant_domains
    ADD COLUMN verification_record_value VARCHAR(255),
    ADD CONSTRAINT ck_prv_tenant_domains_challenge
        CHECK (
            verification_method = 'INTERNAL'
            OR (verification_record_value IS NOT NULL AND verification_token_hash IS NOT NULL)
        ) NOT VALID;

ALTER TABLE prv_tenant_domains
    VALIDATE CONSTRAINT ck_prv_tenant_domains_challenge;

INSERT INTO prv_operation_steps (
    operation_id, step_order, step_key, lifecycle_state, target_service, redacted_result)
SELECT operation.operation_id,
       COALESCE((
           SELECT MAX(existing.step_order) + 1
             FROM prv_operation_steps existing
            WHERE existing.operation_id = operation.operation_id
       ), 1),
       'ACTIVATE_TENANT',
       CASE WHEN operation.lifecycle_state = 'SUCCEEDED' THEN 'SUCCEEDED' ELSE 'PENDING' END,
       'dwp-provider-server',
       CASE
           WHEN operation.lifecycle_state = 'SUCCEEDED' THEN '{"lifecycle":"ACTIVE"}'::jsonb
           ELSE '{}'::jsonb
       END
  FROM prv_operations operation
 WHERE operation.operation_type = 'TENANT_ONBOARD'
   AND NOT EXISTS (
       SELECT 1 FROM prv_operation_steps step
        WHERE step.operation_id = operation.operation_id
          AND step.step_key = 'ACTIVATE_TENANT'
   );
