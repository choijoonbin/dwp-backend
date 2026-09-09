-- Make the contract catalog operationally useful without exposing raw templates or payloads.
-- Every value is governance metadata; delivery content remains recipient scoped.
UPDATE ntf_notification_type_versions type_version
   SET contract_payload = type_version.contract_payload || jsonb_strip_nulls(jsonb_build_object(
       'requiredVariables', CASE type.type_key
           WHEN 'APPROVAL.ACTION_REQUIRED'
               THEN '["requestTitle","requesterName","taskId"]'::jsonb
           WHEN 'APPROVAL.REQUEST_SUBMITTED'
               THEN '["requestTitle","decision","requestId"]'::jsonb
           WHEN 'APPROVAL.REQUEST_APPROVED'
               THEN '["requestTitle","decision","requestId"]'::jsonb
           WHEN 'APPROVAL.REQUEST_REJECTED'
               THEN '["requestTitle","decision","requestId"]'::jsonb
           WHEN 'HCM.LEAVE_APPROVED'
               THEN '["leavePeriod"]'::jsonb
           WHEN 'SPACE.MENTION'
               THEN '["spaceName","senderName","messagePreview","conversationId"]'::jsonb
           WHEN 'MESSAGING.DIRECT_MESSAGE'
               THEN '["senderName","messagePreview","conversationName","conversationId","messageId"]'::jsonb
           WHEN 'MESSAGING.MENTION'
               THEN '["senderName","messagePreview","conversationName","conversationId","messageId"]'::jsonb
           WHEN 'MESSAGING.THREAD_REPLY'
               THEN '["senderName","messagePreview","conversationName","conversationId","messageId"]'::jsonb
           WHEN 'MESSAGING.CHANNEL_MESSAGE'
               THEN '["senderName","messagePreview","conversationName","conversationId","messageId"]'::jsonb
           WHEN 'MEETINGS.INVITATION_CREATED'
               THEN '["meetingId"]'::jsonb
           WHEN 'MEETINGS.INVITATION_RESCHEDULED'
               THEN '["meetingId"]'::jsonb
           WHEN 'MEETINGS.INVITATION_CANCELLED'
               THEN '["meetingId"]'::jsonb
           WHEN 'MEETINGS.PREPARATION_MATERIAL_ADDED'
               THEN '["meetingId"]'::jsonb
           WHEN 'MEETINGS.PREPARATION_MATERIAL_REMOVED'
               THEN '["meetingId"]'::jsonb
           ELSE COALESCE(type_version.contract_payload -> 'requiredVariables', '[]'::jsonb)
       END,
       'dedupeStrategy', COALESCE(
           NULLIF(type_version.contract_payload ->> 'dedupeStrategy', ''),
           'SOURCE_EVENT_RECIPIENT'
       ),
       'retentionPolicy', COALESCE(
           NULLIF(type_version.contract_payload ->> 'retentionPolicy', ''),
           'TENANT_DEFAULT_LEGAL_HOLD_AWARE'
       ),
       'runbookUrl', COALESCE(
           NULLIF(type_version.contract_payload ->> 'runbookUrl', ''),
           '/notifications/admin/operations?typeKey=' || type.type_key
       ),
       'endEventType', CASE type.type_key
           WHEN 'APPROVAL.ACTION_REQUIRED' THEN 'approval.task.decided.v1'
           WHEN 'MEETINGS.INVITATION_CREATED' THEN 'meetings.meeting.cancelled.v1'
           ELSE NULLIF(type_version.contract_payload ->> 'endEventType', '')
       END
   ))
  FROM ntf_notification_types type
 WHERE type.type_id = type_version.type_id;

COMMENT ON COLUMN ntf_notification_type_versions.contract_payload IS
    'Governed notification contract metadata including audience, variables, dedupe, lifecycle, preview, retention and runbook hints.';
