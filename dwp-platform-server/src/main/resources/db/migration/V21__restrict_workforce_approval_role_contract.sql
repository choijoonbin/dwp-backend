UPDATE sys_code_values
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PEOPLE.APPROVAL_ROLE'
   AND code IN ('PEOPLE_ADMIN', 'TENANT_ADMIN', 'PLATFORM_ADMIN');

UPDATE sys_code_sets
   SET description = 'Authentication roles permitted to approve workforce organization design decisions.',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PEOPLE.APPROVAL_ROLE';
