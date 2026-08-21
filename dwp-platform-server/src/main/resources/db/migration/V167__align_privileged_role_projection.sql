UPDATE sys_code_values
   SET behavior_metadata = behavior_metadata || '{"assignableToGroups":false}'::jsonb,
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'AUTH.BUILT_IN_ROLE'
   AND code IN (
       'MAIL_ADMIN',
       'MESSAGING_ADMIN',
       'NOTIFICATION_CONTRACT_OWNER',
       'NOTIFICATION_TEMPLATE_EDITOR',
       'NOTIFICATION_POLICY_APPROVER',
       'NOTIFICATION_OPERATOR');
