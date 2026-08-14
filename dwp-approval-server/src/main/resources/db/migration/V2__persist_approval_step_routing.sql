-- Persist the routing principal with each request step so execution never depends
-- on a mutable workflow or a display-only owner group after submission.
ALTER TABLE apr_steps
    ADD COLUMN IF NOT EXISTS candidate_role VARCHAR(80);

UPDATE apr_steps
   SET candidate_role = 'APPROVAL_OPERATOR'
 WHERE candidate_role IS NULL OR BTRIM(candidate_role) = '';

ALTER TABLE apr_steps
    ALTER COLUMN candidate_role SET DEFAULT 'APPROVAL_OPERATOR',
    ALTER COLUMN candidate_role SET NOT NULL;

ALTER TABLE apr_steps
    DROP CONSTRAINT IF EXISTS ck_apr_step_candidate_role;

ALTER TABLE apr_steps
    ADD CONSTRAINT ck_apr_step_candidate_role CHECK (
        candidate_role ~ '^[A-Z][A-Z0-9_]{1,79}$');

CREATE INDEX IF NOT EXISTS idx_apr_step_next_route
    ON apr_steps (tenant_id, request_id, sequence_number)
    WHERE status = 'WAITING';

-- The original local reference workflows used business group labels that were
-- not authorization role keys. Route those system seeds to the governed
-- approval operator role, while leaving tenant-authored definitions untouched.
WITH normalized AS (
    SELECT version.tenant_id,
           version.workflow_version_id,
           jsonb_set(
               version.definition,
               '{steps}',
               COALESCE((
                   SELECT jsonb_agg(
                       jsonb_set(
                           CASE WHEN step ? 'name'
                                THEN step
                                ELSE jsonb_set(step, '{name}', to_jsonb(step->>'key'))
                           END,
                           '{candidateRole}',
                           to_jsonb('APPROVAL_OPERATOR'::text))
                       ORDER BY ordinal)
                     FROM jsonb_array_elements(version.definition->'steps')
                          WITH ORDINALITY AS item(step, ordinal)
               ), '[]'::jsonb)) AS definition
      FROM apr_workflow_versions version
      JOIN apr_workflow_definitions workflow
        ON workflow.tenant_id = version.tenant_id
       AND workflow.workflow_id = version.workflow_id
     WHERE workflow.created_by = 1
       AND workflow.workflow_key IN (
           'CAPEX_PURCHASE', 'ACCESS_EXCEPTION',
           'SUPPLIER_ONBOARDING', 'GENERAL_DECISION'))
UPDATE apr_workflow_versions version
   SET definition = normalized.definition,
       definition_sha256 = encode(
           sha256(convert_to(normalized.definition::text, 'UTF8')), 'hex')
  FROM normalized
 WHERE version.tenant_id = normalized.tenant_id
   AND version.workflow_version_id = normalized.workflow_version_id;

UPDATE apr_workflow_definitions
   SET owner_group_ref = 'APPROVAL_OPERATOR',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP
 WHERE created_by = 1
   AND workflow_key IN (
       'CAPEX_PURCHASE', 'ACCESS_EXCEPTION',
       'SUPPLIER_ONBOARDING', 'GENERAL_DECISION')
   AND owner_group_ref <> 'APPROVAL_OPERATOR';

CREATE OR REPLACE FUNCTION normalize_seed_approval_candidate_role()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.created_by = 1
       AND NEW.workflow_key IN (
           'CAPEX_PURCHASE', 'ACCESS_EXCEPTION',
           'SUPPLIER_ONBOARDING', 'GENERAL_DECISION') THEN
        NEW.owner_group_ref := 'APPROVAL_OPERATOR';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_apr_normalize_seed_candidate_role
    ON apr_workflow_definitions;

CREATE TRIGGER trg_apr_normalize_seed_candidate_role
BEFORE INSERT ON apr_workflow_definitions
FOR EACH ROW
EXECUTE FUNCTION normalize_seed_approval_candidate_role();
