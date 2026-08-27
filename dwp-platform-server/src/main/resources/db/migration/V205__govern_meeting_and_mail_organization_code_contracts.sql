-- Govern every enum-like CHECK contract in the meeting database and the Java
-- enums introduced by meeting collaboration/content and personal-mail
-- organization. Source databases and Java declarations remain authoritative;
-- this administrator-only registry projection fails closed on ownership,
-- source-identity, value, or binding drift.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp-platform:system-code-registry', 0));

LOCK TABLE sys_code_sets, sys_code_values, sys_code_bindings
    IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE tmp_v205_code_set_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    validation_source VARCHAR(30) NOT NULL,
    contract_kind VARCHAR(24) NOT NULL,
    create_code_set BOOLEAN NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    UNIQUE (owner_service, source_reference),
    CONSTRAINT ck_tmp_v205_values_nonempty
        CHECK (cardinality(allowed_values) > 0)
) ON COMMIT DROP;

INSERT INTO tmp_v205_code_set_manifest VALUES
    -- Reuse the shared infrastructure contracts already governed by dwp-core.
    ('CORE.DOMAIN_EVENT.INBOX_STATUS', 'dwp-core',
     'sys_domain_event_inbox.status', 'CHECK', 'STATE_MACHINE', FALSE,
     ARRAY['RECEIVED', 'PROCESSING', 'DEFERRED', 'REPLAY_PENDING',
           'SUCCEEDED', 'DUPLICATE', 'FAILED', 'DEAD']::VARCHAR[]),
    ('CORE.DOMAIN_EVENT.OUTBOX_STATUS', 'dwp-core',
     'sys_domain_event_outbox.status', 'CHECK', 'STATE_MACHINE', FALSE,
     ARRAY['PENDING', 'SENDING', 'PUBLISHED', 'FAILED', 'DEAD']::VARCHAR[]),
    ('CORE.DOMAIN_EVENT.REPLAY_DIRECTION', 'dwp-core',
     'sys_domain_event_replay_audit.direction', 'CHECK', 'PROTOCOL', FALSE,
     ARRAY['INBOX', 'OUTBOX']::VARCHAR[]),

    -- Existing typed meeting sets from V198 remain Java-owned.
    ('MEETING.VIDEO_MEETING.ACCESS_SCOPE', 'dwp-meeting-server',
     'VideoMeetingModels.AccessScope', 'TYPED_CONTRACT', 'SECURITY', FALSE,
     ARRAY['INTERNAL', 'INVITED', 'PUBLIC_CODE']::VARCHAR[]),
    ('MEETING.VIDEO_MEETING.ATTENDANCE_STATE', 'dwp-meeting-server',
     'VideoMeetingModels.AttendanceState', 'TYPED_CONTRACT',
     'STATE_MACHINE', FALSE,
     ARRAY['INVITED', 'REQUESTED', 'ADMITTED', 'DENIED', 'JOINED',
           'LEFT']::VARCHAR[]),
    ('MEETING.VIDEO_MEETING.LIFECYCLE_STATE', 'dwp-meeting-server',
     'VideoMeetingModels.LifecycleState', 'TYPED_CONTRACT',
     'STATE_MACHINE', FALSE,
     ARRAY['DRAFT', 'SCHEDULED', 'LOBBY', 'LIVE', 'ENDED',
           'CANCELLED']::VARCHAR[]),
    ('MEETING.VIDEO_MEETING.PARTICIPANT_ROLE', 'dwp-meeting-server',
     'VideoMeetingModels.ParticipantRole', 'TYPED_CONTRACT', 'SECURITY',
     FALSE,
     ARRAY['ORGANIZER', 'CO_HOST', 'PRESENTER', 'ATTENDEE',
           'GUEST']::VARCHAR[]),

    -- New Java-owned meeting collaboration and content contracts.
    ('MEETING.VIDEO_MEETING.CHAT_MESSAGE_STATE', 'dwp-meeting-server',
     'VideoMeetingCollaborationModels.ChatMessageState', 'TYPED_CONTRACT',
     'STATE_MACHINE', TRUE, ARRAY['ACTIVE', 'DELETED']::VARCHAR[]),
    ('MEETING.VIDEO_MEETING.HAND_REQUEST_STATE', 'dwp-meeting-server',
     'VideoMeetingCollaborationModels.HandRequestState', 'TYPED_CONTRACT',
     'STATE_MACHINE', TRUE,
     ARRAY['RAISED', 'ACKNOWLEDGED', 'LOWERED', 'DISMISSED',
           'CLEARED']::VARCHAR[]),
    ('MEETING.VIDEO_MEETING.CONTENT_PLAN_STATE', 'dwp-meeting-server',
     'VideoMeetingContentModels.PlanState', 'TYPED_CONTRACT',
     'STATE_MACHINE', TRUE, ARRAY['DISABLED', 'BLOCKED', 'READY']::VARCHAR[]),
    ('MEETING.VIDEO_MEETING.CONTENT_NOTICE_STATE', 'dwp-meeting-server',
     'VideoMeetingContentModels.NoticeState', 'TYPED_CONTRACT',
     'STATE_MACHINE', TRUE, ARRAY['PUBLISHED', 'SUPERSEDED']::VARCHAR[]),
    ('MEETING.VIDEO_MEETING.RECORDING_STATE', 'dwp-meeting-server',
     'VideoMeetingContentModels.RecordingState', 'TYPED_CONTRACT',
     'STATE_MACHINE', TRUE,
     ARRAY['REQUESTED', 'STARTING', 'RECORDING', 'STOP_REQUESTED',
           'STOPPED', 'FAILED']::VARCHAR[]),
    ('MEETING.VIDEO_MEETING.CONTENT_BLOCKER_CODE', 'dwp-meeting-server',
     'VideoMeetingContentModels.BlockerCode', 'TYPED_CONTRACT', 'PROTOCOL',
     TRUE,
     ARRAY['MEETINGS_DISABLED', 'PLAN_RECORDING_DISABLED',
           'MEETING_NOT_LIVE', 'POLICY_NEVER', 'E2EE', 'CONSENT',
           'RECORDING_NOT_ACTIVE', 'MEDIA_PROVIDER', 'EGRESS', 'STORAGE',
           'KMS', 'AUDIT', 'STT', 'LLM']::VARCHAR[]),

    -- Meeting CHECK-only contracts without a Java enum declaration.
    ('MEETING.SYS_AUDIT_OUTBOX.STATUS', 'dwp-meeting-server',
     'sys_audit_outbox.status', 'CHECK', 'STATE_MACHINE', TRUE,
     ARRAY['PENDING', 'SENDING', 'PUBLISHED', 'FAILED', 'DEAD']::VARCHAR[]),
    ('MEETING.VM_MEETING_ARTIFACTS.ARTIFACT_STATE', 'dwp-meeting-server',
     'vm_meeting_artifacts.artifact_state', 'CHECK', 'STATE_MACHINE', TRUE,
     ARRAY['NONE', 'PROCESSING', 'AVAILABLE', 'FAILED', 'UNAVAILABLE',
           'DELETED']::VARCHAR[]),
    ('MEETING.VM_MEETING_ARTIFACTS.ARTIFACT_TYPE', 'dwp-meeting-server',
     'vm_meeting_artifacts.artifact_type', 'CHECK', 'REFERENCE', TRUE,
     ARRAY['RECORDING', 'TRANSCRIPT', 'SUMMARY', 'ATTENDANCE',
           'CHAT_EXPORT']::VARCHAR[]),
    ('MEETING.VM_MEETING_COLLABORATION_COMMANDS.COMMAND_TYPE',
     'dwp-meeting-server', 'vm_meeting_collaboration_commands.command_type',
     'CHECK', 'PROTOCOL', TRUE,
     ARRAY['CHAT_SEND', 'CHAT_DELETE', 'HAND_RAISE', 'HAND_ACKNOWLEDGE',
           'HAND_LOWER', 'HAND_DISMISS', 'HAND_CLEAR']::VARCHAR[]),
    ('MEETING.VM_MEETING_CONTENT_COMMANDS.COMMAND_OUTCOME',
     'dwp-meeting-server', 'vm_meeting_content_commands.command_outcome',
     'CHECK', 'STATE_MACHINE', TRUE, ARRAY['ACCEPTED', 'BLOCKED']::VARCHAR[]),
    ('MEETING.VM_MEETING_CONTENT_COMMANDS.COMMAND_TYPE',
     'dwp-meeting-server', 'vm_meeting_content_commands.command_type',
     'CHECK', 'PROTOCOL', TRUE,
     ARRAY['PLAN_UPDATE', 'NOTICE_ACK', 'RECORDING_REQUEST',
           'RECORDING_STOP']::VARCHAR[]),
    ('MEETING.VM_MEETING_EVENTS.EVENT_TYPE', 'dwp-meeting-server',
     'vm_meeting_events.event_type', 'CHECK', 'PROTOCOL', TRUE,
     ARRAY['CREATED', 'SCHEDULED', 'JOIN_REQUESTED', 'ADMITTED', 'DENIED',
           'JOINED', 'LEFT', 'UNMUTE_REQUESTED', 'STARTED', 'ENDED',
           'CANCELLED', 'POLICY_UPDATED', 'TOKEN_ISSUED']::VARCHAR[]),
    ('MEETING.VM_MEETINGS.MEETING_KIND', 'dwp-meeting-server',
     'vm_meetings.meeting_kind', 'CHECK', 'REFERENCE', TRUE,
     ARRAY['FORMAL_MEETING']::VARCHAR[]),
    ('MEETING.VM_PEOPLE_SNAPSHOT.LIFECYCLE_STATE', 'dwp-meeting-server',
     'vm_people_snapshot.lifecycle_state', 'CHECK', 'STATE_MACHINE', TRUE,
     ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('MEETING.VM_TENANT_POLICIES.RECORDING_POLICY', 'dwp-meeting-server',
     'vm_tenant_policies.recording_policy', 'CHECK', 'SECURITY', TRUE,
     ARRAY['NEVER', 'HOST_OPT_IN', 'ADMIN_REQUIRED']::VARCHAR[]),
    ('MEETING.VM_TENANT_POLICIES.UNMUTE_CONTROL', 'dwp-meeting-server',
     'vm_tenant_policies.unmute_control', 'CHECK', 'SECURITY', TRUE,
     ARRAY['REQUEST_ONLY']::VARCHAR[]),

    -- V203 CHECK sets also govern their exact MailOrganizationTypes enums.
    ('PLATFORM.MAIL_FOLDERS.COLOR_TOKEN', 'dwp-platform-server',
     'mail_folders.color_token', 'CHECK', 'REFERENCE', FALSE,
     ARRAY['NEUTRAL', 'BLUE', 'TEAL', 'GREEN', 'AMBER', 'CORAL',
           'VIOLET']::VARCHAR[]),
    ('PLATFORM.MAIL_FOLDERS.PROVIDER_SYNC_STATE', 'dwp-platform-server',
     'mail_folders.provider_sync_state', 'CHECK', 'STATE_MACHINE', FALSE,
     ARRAY['LOCAL_ONLY', 'PENDING', 'SYNCED', 'ERROR']::VARCHAR[]),
    ('PLATFORM.MAIL_RULES.MATCH_MODE', 'dwp-platform-server',
     'mail_rules.match_mode', 'CHECK', 'REFERENCE', FALSE,
     ARRAY['ALL', 'ANY']::VARCHAR[]),
    ('PLATFORM.MAIL_RULES.SYNCHRONIZATION_STATE', 'dwp-platform-server',
     'mail_rules.synchronization_state', 'CHECK', 'STATE_MACHINE', FALSE,
     ARRAY['LOCAL_ONLY', 'PENDING', 'SYNCED', 'ERROR']::VARCHAR[]),

    -- Mail JSON command vocabularies have no scalar DB CHECK binding.
    ('PLATFORM.MAIL_ORGANIZATION.RULE_FIELD', 'dwp-platform-server',
     'MailOrganizationTypes.RuleField', 'TYPED_CONTRACT', 'REFERENCE', TRUE,
     ARRAY['SENDER', 'RECIPIENT', 'SUBJECT', 'BODY', 'HAS_ATTACHMENT',
           'IMPORTANCE']::VARCHAR[]),
    ('PLATFORM.MAIL_ORGANIZATION.RULE_OPERATOR', 'dwp-platform-server',
     'MailOrganizationTypes.RuleOperator', 'TYPED_CONTRACT', 'PROTOCOL', TRUE,
     ARRAY['CONTAINS', 'EQUALS', 'STARTS_WITH', 'ENDS_WITH', 'IS']::VARCHAR[]),
    ('PLATFORM.MAIL_ORGANIZATION.RULE_ACTION_TYPE', 'dwp-platform-server',
     'MailOrganizationTypes.RuleActionType', 'TYPED_CONTRACT', 'PROTOCOL',
     TRUE,
     ARRAY['MOVE_TO_FOLDER', 'MARK_READ', 'STAR',
           'SET_IMPORTANCE']::VARCHAR[]),
    ('PLATFORM.MAIL_ORGANIZATION.LIFECYCLE_ACTION', 'dwp-platform-server',
     'MailOrganizationTypes.LifecycleAction', 'TYPED_CONTRACT', 'PROTOCOL',
     TRUE,
     ARRAY['MOVE', 'ARCHIVE', 'TRASH', 'SPAM', 'RESTORE',
           'DELETE_FOREVER']::VARCHAR[]),

    -- The Widget Registry internal trust plane is a closed security protocol.
    ('PLATFORM.WIDGET_REGISTRY.INGRESS_FAILURE', 'dwp-platform-server',
     'WidgetRegistryIngressFailure', 'TYPED_CONTRACT', 'SECURITY', TRUE,
     ARRAY['ROUTE_NOT_FOUND', 'METHOD_NOT_ALLOWED', 'TLS_REQUIRED',
           'PROVISIONING_TOKEN_FORBIDDEN', 'DUAL_PROOF_REQUIRED',
           'SERVICE_TOKEN_INVALID', 'ASSERTION_INVALID',
           'REQUEST_BINDING_INVALID', 'PAYLOAD_TOO_LARGE',
           'ASSERTION_REPLAYED', 'TRUST_UNAVAILABLE']::VARCHAR[]),
    ('PLATFORM.WIDGET_REGISTRY.INTERNAL_ROUTE_RESOLUTION_STATUS',
     'dwp-platform-server', 'WidgetRegistryInternalRoutes.ResolutionStatus',
     'TYPED_CONTRACT', 'PROTOCOL', TRUE,
     ARRAY['MATCHED', 'NOT_FOUND', 'METHOD_NOT_ALLOWED']::VARCHAR[]),
    ('PLATFORM.WIDGET_REGISTRY.INTERNAL_ROUTE', 'dwp-platform-server',
     'WidgetRegistryInternalRoutes.Route', 'TYPED_CONTRACT', 'SECURITY', TRUE,
     ARRAY['LIST_DEFINITIONS', 'GET_DEFINITION',
           'LIST_DEFINITION_VERSIONS', 'GET_VERSION',
           'GET_RETIREMENT_IMPACT', 'GET_RELEASE_CHANNEL', 'LIST_EVIDENCE',
           'GET_EVIDENCE', 'LIST_RUNTIME_CONTROLS',
           'GET_COMMAND_COMPLETION', 'SEAL_COMMAND_NOT_EXECUTED',
           'EXECUTE_COMMAND']::VARCHAR[]),
    ('PLATFORM.WIDGET_REGISTRY.ASSERTION_KIND', 'dwp-platform-server',
     'WidgetRegistryTrustPorts.AssertionKind', 'TYPED_CONTRACT', 'SECURITY',
     TRUE, ARRAY['WIDGET', 'RECONCILE']::VARCHAR[]),
    ('PLATFORM.WIDGET_REGISTRY.REPLAY_DECISION', 'dwp-platform-server',
     'WidgetRegistryTrustPorts.ReplayDecision', 'TYPED_CONTRACT',
     'STATE_MACHINE', TRUE,
     ARRAY['ACCEPTED', 'REPLAYED', 'UNAVAILABLE']::VARCHAR[]),
    ('PLATFORM.WIDGET_REGISTRY.VERIFICATION_FAILURE', 'dwp-platform-server',
     'WidgetRegistryTrustPorts.VerificationFailure', 'TYPED_CONTRACT',
     'SECURITY', TRUE, ARRAY['INVALID', 'TRUST_UNAVAILABLE']::VARCHAR[]),

    -- Approval and Auth deliberately share this wire contract.
    ('AUTH.PRODUCT_SURFACE.ACCESS_MODE', 'dwp-auth-server',
     'ProductSurfaceAuthorityDtos.AccessMode', 'TYPED_CONTRACT', 'SECURITY',
     FALSE, ARRAY['NORMAL', 'ELEVATED', 'PROVIDER_SUPPORT']::VARCHAR[]);

CREATE TEMP TABLE tmp_v205_database_binding_manifest (
    code_set_key VARCHAR(100) NOT NULL,
    consumer_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(300) NOT NULL,
    PRIMARY KEY (consumer_service, source_reference)
) ON COMMIT DROP;

INSERT INTO tmp_v205_database_binding_manifest VALUES
    ('MEETING.SYS_AUDIT_OUTBOX.STATUS', 'dwp-meeting-server',
     'sys_audit_outbox.status'),
    ('CORE.DOMAIN_EVENT.INBOX_STATUS', 'dwp-meeting-server',
     'sys_domain_event_inbox.status'),
    ('CORE.DOMAIN_EVENT.OUTBOX_STATUS', 'dwp-meeting-server',
     'sys_domain_event_outbox.status'),
    ('CORE.DOMAIN_EVENT.REPLAY_DIRECTION', 'dwp-meeting-server',
     'sys_domain_event_replay_audit.direction'),
    ('MEETING.VM_MEETING_ARTIFACTS.ARTIFACT_STATE', 'dwp-meeting-server',
     'vm_meeting_artifacts.artifact_state'),
    ('MEETING.VM_MEETING_ARTIFACTS.ARTIFACT_TYPE', 'dwp-meeting-server',
     'vm_meeting_artifacts.artifact_type'),
    ('MEETING.VIDEO_MEETING.CHAT_MESSAGE_STATE', 'dwp-meeting-server',
     'vm_meeting_chat_messages.message_state'),
    ('MEETING.VIDEO_MEETING.PARTICIPANT_ROLE', 'dwp-meeting-server',
     'vm_meeting_chat_messages.sender_role'),
    ('MEETING.VM_MEETING_COLLABORATION_COMMANDS.COMMAND_TYPE',
     'dwp-meeting-server', 'vm_meeting_collaboration_commands.command_type'),
    ('MEETING.VM_MEETING_CONTENT_COMMANDS.COMMAND_OUTCOME',
     'dwp-meeting-server', 'vm_meeting_content_commands.command_outcome'),
    ('MEETING.VM_MEETING_CONTENT_COMMANDS.COMMAND_TYPE',
     'dwp-meeting-server', 'vm_meeting_content_commands.command_type'),
    ('MEETING.VIDEO_MEETING.CONTENT_NOTICE_STATE', 'dwp-meeting-server',
     'vm_meeting_content_notices.notice_state'),
    ('MEETING.VIDEO_MEETING.CONTENT_PLAN_STATE', 'dwp-meeting-server',
     'vm_meeting_content_plans.plan_state'),
    ('MEETING.VM_MEETING_EVENTS.EVENT_TYPE', 'dwp-meeting-server',
     'vm_meeting_events.event_type'),
    ('MEETING.VIDEO_MEETING.HAND_REQUEST_STATE', 'dwp-meeting-server',
     'vm_meeting_hand_events.event_type'),
    ('MEETING.VIDEO_MEETING.HAND_REQUEST_STATE', 'dwp-meeting-server',
     'vm_meeting_hand_requests.request_state'),
    ('MEETING.VIDEO_MEETING.PARTICIPANT_ROLE', 'dwp-meeting-server',
     'vm_meeting_hand_requests.requester_role'),
    ('MEETING.VIDEO_MEETING.ATTENDANCE_STATE', 'dwp-meeting-server',
     'vm_meeting_participants.attendance_state'),
    ('MEETING.VIDEO_MEETING.PARTICIPANT_ROLE', 'dwp-meeting-server',
     'vm_meeting_participants.participant_role'),
    ('MEETING.VIDEO_MEETING.RECORDING_STATE', 'dwp-meeting-server',
     'vm_meeting_recording_sessions.recording_state'),
    ('MEETING.VIDEO_MEETING.ACCESS_SCOPE', 'dwp-meeting-server',
     'vm_meetings.access_scope'),
    ('MEETING.VIDEO_MEETING.LIFECYCLE_STATE', 'dwp-meeting-server',
     'vm_meetings.lifecycle_state'),
    ('MEETING.VM_MEETINGS.MEETING_KIND', 'dwp-meeting-server',
     'vm_meetings.meeting_kind'),
    ('MEETING.VM_PEOPLE_SNAPSHOT.LIFECYCLE_STATE', 'dwp-meeting-server',
     'vm_people_snapshot.lifecycle_state'),
    ('MEETING.VM_TENANT_POLICIES.RECORDING_POLICY', 'dwp-meeting-server',
     'vm_tenant_policies.recording_policy'),
    ('MEETING.VM_TENANT_POLICIES.UNMUTE_CONTROL', 'dwp-meeting-server',
     'vm_tenant_policies.unmute_control');

CREATE TEMP TABLE tmp_v205_typed_binding_manifest (
    code_set_key VARCHAR(100) NOT NULL,
    consumer_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(300) NOT NULL,
    usage_type VARCHAR(30) NOT NULL DEFAULT 'API_CONTRACT',
    PRIMARY KEY (code_set_key, consumer_service, source_reference)
) ON COMMIT DROP;

INSERT INTO tmp_v205_typed_binding_manifest (
    code_set_key, consumer_service, source_reference)
VALUES
    ('MEETING.VIDEO_MEETING.ACCESS_SCOPE', 'dwp-meeting-server',
     'VideoMeetingModels.AccessScope'),
    ('MEETING.VIDEO_MEETING.ATTENDANCE_STATE', 'dwp-meeting-server',
     'VideoMeetingModels.AttendanceState'),
    ('MEETING.VIDEO_MEETING.LIFECYCLE_STATE', 'dwp-meeting-server',
     'VideoMeetingModels.LifecycleState'),
    ('MEETING.VIDEO_MEETING.PARTICIPANT_ROLE', 'dwp-meeting-server',
     'VideoMeetingModels.ParticipantRole'),
    ('MEETING.VIDEO_MEETING.CHAT_MESSAGE_STATE', 'dwp-meeting-server',
     'VideoMeetingCollaborationModels.ChatMessageState'),
    ('MEETING.VIDEO_MEETING.HAND_REQUEST_STATE', 'dwp-meeting-server',
     'VideoMeetingCollaborationModels.HandRequestState'),
    ('MEETING.VIDEO_MEETING.CONTENT_PLAN_STATE', 'dwp-meeting-server',
     'VideoMeetingContentModels.PlanState'),
    ('MEETING.VIDEO_MEETING.CONTENT_NOTICE_STATE', 'dwp-meeting-server',
     'VideoMeetingContentModels.NoticeState'),
    ('MEETING.VIDEO_MEETING.RECORDING_STATE', 'dwp-meeting-server',
     'VideoMeetingContentModels.RecordingState'),
    ('MEETING.VIDEO_MEETING.CONTENT_BLOCKER_CODE', 'dwp-meeting-server',
     'VideoMeetingContentModels.BlockerCode'),
    ('PLATFORM.MAIL_FOLDERS.COLOR_TOKEN', 'dwp-platform-server',
     'MailOrganizationTypes.FolderColor'),
    ('PLATFORM.MAIL_FOLDERS.PROVIDER_SYNC_STATE', 'dwp-platform-server',
     'MailOrganizationTypes.ProviderSyncState'),
    ('PLATFORM.MAIL_RULES.SYNCHRONIZATION_STATE', 'dwp-platform-server',
     'MailOrganizationTypes.ProviderSyncState'),
    ('PLATFORM.MAIL_RULES.MATCH_MODE', 'dwp-platform-server',
     'MailOrganizationTypes.RuleMatchMode'),
    ('PLATFORM.MAIL_ORGANIZATION.RULE_FIELD', 'dwp-platform-server',
     'MailOrganizationTypes.RuleField'),
    ('PLATFORM.MAIL_ORGANIZATION.RULE_OPERATOR', 'dwp-platform-server',
     'MailOrganizationTypes.RuleOperator'),
    ('PLATFORM.MAIL_ORGANIZATION.RULE_ACTION_TYPE', 'dwp-platform-server',
     'MailOrganizationTypes.RuleActionType'),
    ('PLATFORM.MAIL_ORGANIZATION.LIFECYCLE_ACTION', 'dwp-platform-server',
     'MailOrganizationTypes.LifecycleAction'),
    ('PLATFORM.WIDGET_REGISTRY.INGRESS_FAILURE', 'dwp-platform-server',
     'WidgetRegistryIngressFailure'),
    ('PLATFORM.WIDGET_REGISTRY.INTERNAL_ROUTE_RESOLUTION_STATUS',
     'dwp-platform-server', 'WidgetRegistryInternalRoutes.ResolutionStatus'),
    ('PLATFORM.WIDGET_REGISTRY.INTERNAL_ROUTE', 'dwp-platform-server',
     'WidgetRegistryInternalRoutes.Route'),
    ('PLATFORM.WIDGET_REGISTRY.ASSERTION_KIND', 'dwp-platform-server',
     'WidgetRegistryTrustPorts.AssertionKind'),
    ('PLATFORM.WIDGET_REGISTRY.REPLAY_DECISION', 'dwp-platform-server',
     'WidgetRegistryTrustPorts.ReplayDecision'),
    ('PLATFORM.WIDGET_REGISTRY.VERIFICATION_FAILURE', 'dwp-platform-server',
     'WidgetRegistryTrustPorts.VerificationFailure'),
    ('AUTH.PRODUCT_SURFACE.ACCESS_MODE', 'dwp-approval-server',
     'ApprovalPilotPepRegistry.ActiveAccessMode');

UPDATE tmp_v205_typed_binding_manifest
   SET usage_type = 'BEHAVIOR'
 WHERE source_reference IN (
       'WidgetRegistryInternalRoutes.ResolutionStatus',
       'WidgetRegistryInternalRoutes.Route',
       'WidgetRegistryTrustPorts.ReplayDecision',
       'WidgetRegistryTrustPorts.VerificationFailure');

DO $v205_preflight$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v205_code_set_manifest manifest
          LEFT JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE NOT manifest.create_code_set
           AND code_set.code_set_key IS NULL
    ) THEN
        RAISE EXCEPTION 'V205 requires an existing reusable code set';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v205_code_set_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE ROW(code_set.owner_service, code_set.source_reference,
                   code_set.validation_source, code_set.contract_kind,
                   code_set.configuration_level, code_set.runtime_visibility,
                   code_set.lifecycle_state)
               IS DISTINCT FROM
               ROW(manifest.owner_service, manifest.source_reference,
                   manifest.validation_source, manifest.contract_kind,
                   'SYSTEM', 'ADMIN_ONLY', 'ACTIVE')
    ) OR EXISTS (
        SELECT 1
          FROM tmp_v205_code_set_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.owner_service = manifest.owner_service
           AND code_set.source_reference = manifest.source_reference
         WHERE code_set.code_set_key <> manifest.code_set_key
    ) THEN
        RAISE EXCEPTION
            'V205 code-set ownership, source, or canonical metadata drifted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v205_code_set_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE (SELECT array_agg(value_ref.code ORDER BY value_ref.code)
                  FROM sys_code_values value_ref
                 WHERE value_ref.code_set_key = manifest.code_set_key
                   AND value_ref.lifecycle_state = 'ACTIVE')
               IS DISTINCT FROM
               (SELECT array_agg(expected.code ORDER BY expected.code)
                  FROM unnest(manifest.allowed_values) expected(code))
    ) THEN
        RAISE EXCEPTION 'V205 reusable or existing code-set values drifted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v205_database_binding_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = manifest.consumer_service
           AND binding.usage_type = 'DATABASE_COLUMN'
           AND binding.source_reference = manifest.source_reference
         WHERE binding.code_set_key <> manifest.code_set_key
            OR binding.enforcement_type <> 'CHECK'
            OR binding.lifecycle_state <> 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'V205 found a conflicting meeting CHECK binding';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v205_typed_binding_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = manifest.consumer_service
           AND binding.source_reference = manifest.source_reference
         WHERE binding.enforcement_type <> 'TYPED_CONTRACT'
            OR binding.lifecycle_state <> 'ACTIVE'
            OR NOT EXISTS (
                 SELECT 1
                   FROM tmp_v205_typed_binding_manifest allowed_binding
                  WHERE allowed_binding.code_set_key = binding.code_set_key
                    AND allowed_binding.consumer_service =
                        binding.consumer_service
                    AND allowed_binding.usage_type = binding.usage_type
                    AND allowed_binding.source_reference =
                        binding.source_reference)
    ) THEN
        RAISE EXCEPTION 'V205 found a conflicting typed-contract binding';
    END IF;
END;
$v205_preflight$;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT manifest.code_set_key, manifest.owner_service,
       manifest.source_reference,
       CASE manifest.validation_source
           WHEN 'CHECK' THEN
               'Database CHECK contract for ' || manifest.source_reference || '.'
           ELSE
               'Exact Java enum contract for ' || manifest.source_reference || '.'
       END,
       'SYSTEM', manifest.validation_source, manifest.source_reference,
       manifest.contract_kind, 'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v205_code_set_manifest manifest
 WHERE manifest.create_code_set
ON CONFLICT (code_set_key) DO NOTHING;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
SELECT manifest.code_set_key, value_ref.code, value_ref.code,
       jsonb_build_object('ko', value_ref.code, 'en', value_ref.code),
       '{}'::jsonb, value_ref.ordinality::INTEGER * 10, TRUE, 'ACTIVE'
  FROM tmp_v205_code_set_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
 WHERE manifest.create_code_set
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key, manifest.consumer_service, 'DATABASE_COLUMN',
       manifest.source_reference, 'CHECK', 'ACTIVE'
  FROM tmp_v205_database_binding_manifest manifest
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO NOTHING;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key, manifest.consumer_service, manifest.usage_type,
       manifest.source_reference, 'TYPED_CONTRACT', 'ACTIVE'
  FROM tmp_v205_typed_binding_manifest manifest
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO NOTHING;

DO $v205_postcondition$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v205_code_set_manifest manifest
          LEFT JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.code_set_key IS NULL
            OR ROW(code_set.owner_service, code_set.source_reference,
                   code_set.validation_source, code_set.contract_kind,
                   code_set.configuration_level, code_set.runtime_visibility,
                   code_set.lifecycle_state)
               IS DISTINCT FROM
               ROW(manifest.owner_service, manifest.source_reference,
                   manifest.validation_source, manifest.contract_kind,
                   'SYSTEM', 'ADMIN_ONLY', 'ACTIVE')
            OR (SELECT array_agg(value_ref.code ORDER BY value_ref.code)
                  FROM sys_code_values value_ref
                 WHERE value_ref.code_set_key = manifest.code_set_key
                   AND value_ref.lifecycle_state = 'ACTIVE')
               IS DISTINCT FROM
               (SELECT array_agg(expected.code ORDER BY expected.code)
                  FROM unnest(manifest.allowed_values) expected(code))
    ) OR EXISTS (
        SELECT 1
          FROM tmp_v205_database_binding_manifest manifest
         WHERE (SELECT COUNT(*)
                  FROM sys_code_bindings binding
                 WHERE binding.code_set_key = manifest.code_set_key
                   AND binding.consumer_service = manifest.consumer_service
                   AND binding.usage_type = 'DATABASE_COLUMN'
                   AND binding.source_reference = manifest.source_reference
                   AND binding.enforcement_type = 'CHECK'
                   AND binding.lifecycle_state = 'ACTIVE') <> 1
    ) OR EXISTS (
        SELECT 1
          FROM tmp_v205_typed_binding_manifest manifest
         WHERE (SELECT COUNT(*)
                  FROM sys_code_bindings binding
                 WHERE binding.code_set_key = manifest.code_set_key
                   AND binding.consumer_service = manifest.consumer_service
                   AND binding.usage_type = manifest.usage_type
                   AND binding.source_reference = manifest.source_reference
                   AND binding.enforcement_type = 'TYPED_CONTRACT'
                   AND binding.lifecycle_state = 'ACTIVE') <> 1
    ) THEN
        RAISE EXCEPTION 'V205 code-contract registry convergence failed';
    END IF;
END;
$v205_postcondition$;
