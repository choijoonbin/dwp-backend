-- V24 expands the effective graph at the existing demo scenario baseline date.
-- Keep the two governed reference alternatives usable by binding them to the
-- fingerprint produced by that complete, deterministic reference graph.
UPDATE ppl_organization_scenarios
   SET baseline_fingerprint = '1d0d3b1fcb924613144ace8c070da51b630f7d5e9aa11cc2bfc9d5d8ed484809',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE tenant_id = 1
   AND scenario_key IN ('ai-scale-up-2027', 'ai-scale-up-2027-growth')
   AND baseline_date = DATE '2026-08-10'
   AND lifecycle_state = 'DRAFT'
   AND baseline_fingerprint = '4f8fe7c494b58d6215711d63875b1748d2ea6b5795a2ba34ecf00dd31fe4572c';
