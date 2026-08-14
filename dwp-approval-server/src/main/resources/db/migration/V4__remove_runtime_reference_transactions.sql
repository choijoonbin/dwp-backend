-- Transactional approval data must come from commands, integrations, or an
-- explicitly enabled environment seed. The V1 helper created synthetic rows
-- while serving read requests, which made reads stateful and mixed fake user
-- identifiers into the approval ledger.
DELETE FROM apr_integration_outbox outbox
 USING apr_requests request
 WHERE outbox.tenant_id = request.tenant_id
   AND outbox.request_id = request.request_id
   AND (request.reference_seed_key LIKE 'task:%'
        OR request.reference_seed_key LIKE 'own:%');

DELETE FROM apr_requests
 WHERE reference_seed_key LIKE 'task:%'
    OR reference_seed_key LIKE 'own:%';

DROP FUNCTION IF EXISTS seed_approval_reference_data(BIGINT, BIGINT, UUID);
