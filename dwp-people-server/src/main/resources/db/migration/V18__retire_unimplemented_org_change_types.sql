-- Keep the public scenario catalog aligned with executable behavior.
-- These types remain reserved for backward-compatible future activation.
UPDATE ppl_organization_change_type_catalog
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE change_type IN (
       'RENAME_ORGANIZATION',
       'CHANGE_ORGANIZATION_LEADER',
       'MOVE_ASSIGNMENT',
       'CHANGE_MANAGER')
   AND lifecycle_state <> 'RETIRED';

COMMENT ON TABLE ppl_organization_change_type_catalog IS
    'Governed scenario operations. Only ACTIVE rows have complete API, projection, validation, and publish behavior.';
