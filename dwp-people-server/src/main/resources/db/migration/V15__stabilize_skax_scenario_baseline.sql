UPDATE ppl_organization_scenarios
   SET baseline_fingerprint = 'cd0655bd2a090fe1420027a6b0c1cbac9722075df7dd8dd617ea787ae9f2a35f',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE tenant_id = 1
   AND scenario_key = 'ai-scale-up-2027'
   AND lifecycle_state = 'DRAFT';
