-- Govern the Java enums added by the provider support, product-surface,
-- calendar collaboration, meeting, and telemetry work. Enum declarations are
-- the source of truth: values and labels are copied verbatim and remain
-- administrator-only. Shared wire contracts intentionally reuse one code set.

CREATE TEMP TABLE tmp_v198_typed_contract_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    contract_kind VARCHAR(30) NOT NULL,
    create_code_set BOOLEAN NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    CONSTRAINT ck_tmp_v198_allowed_values_nonempty
        CHECK (cardinality(allowed_values) > 0)
) ON COMMIT DROP;

INSERT INTO tmp_v198_typed_contract_manifest (
    code_set_key, owner_service, source_reference,
    contract_kind, create_code_set, allowed_values)
VALUES
    ('AUTH.PRODUCT_AUTHORIZATION.OPERATION_LANE',
     'dwp-auth-server', 'ProductAuthorizationOperationsSecurityConfig.Lane',
     'SECURITY', TRUE,
     ARRAY['APPROVAL', 'ACTIVATION', 'UNKNOWN']::VARCHAR[]),
    ('AUTH.GOVERNED_ROUTE.DECISION',
     'dwp-auth-server', 'GovernedRouteAuthorityDtos.Decision',
     'SECURITY', TRUE,
     ARRAY['ALLOWED', 'ROUTE_DENIED', 'EXPIRED', 'STEP_UP_REQUIRED',
           'SOD_CONFLICT', 'AUTHORITY_UNAVAILABLE']::VARCHAR[]),
    ('AUTH.PRODUCT_SURFACE.ACCESS_MODE',
     'dwp-auth-server', 'ProductSurfaceAuthorityDtos.AccessMode',
     'SECURITY', TRUE,
     ARRAY['NORMAL', 'ELEVATED', 'PROVIDER_SUPPORT']::VARCHAR[]),
    ('AUTH.PRODUCT_SURFACE.ACCESS_SOURCE',
     'dwp-auth-server', 'ProductSurfaceAuthorityDtos.AccessSource',
     'SECURITY', TRUE,
     ARRAY['ENTITLEMENT', 'RELATIONSHIP', 'MANAGEMENT', 'SUPPORT']::VARCHAR[]),
    ('AUTH.PRODUCT_SURFACE.ACTIVATION_STATE',
     'dwp-auth-server', 'ProductSurfaceAuthorityDtos.ActivationState',
     'STATE_MACHINE', TRUE,
     ARRAY['ACTIVE', 'ELIGIBLE', 'EXPIRED', 'REVOKED']::VARCHAR[]),
    ('AUTH.PRODUCT_SURFACE.CAPABILITY_AUTHORITY_MODE',
     'dwp-auth-server', 'ProductSurfaceAuthorityDtos.CapabilityAuthorityMode',
     'SECURITY', TRUE,
     ARRAY['PERMISSION', 'PERMISSION_AND_RELATIONSHIP',
           'PERMISSION_OR_RELATIONSHIP']::VARCHAR[]),
    ('AUTH.PRODUCT_SURFACE.DECISION',
     'dwp-auth-server', 'ProductSurfaceAuthorityDtos.Decision',
     'SECURITY', TRUE,
     ARRAY['ALLOWED', 'APP_DENIED', 'SURFACE_DENIED', 'ROUTE_DENIED',
           'SCOPE_SELECTION_REQUIRED', 'SCOPE_INVALID', 'EXPIRED',
           'ACTIVATION_REQUIRED', 'STEP_UP_REQUIRED', 'SOD_CONFLICT',
           'SUPPORT_SCOPE_DENIED', 'AUTHORITY_UNAVAILABLE']::VARCHAR[]),
    ('AUTH.PRODUCT_SURFACE.POLICY_AUTHORITY_MODE',
     'dwp-auth-server', 'ProductSurfaceAuthorityDtos.PolicyAuthorityMode',
     'SECURITY', TRUE,
     ARRAY['ENTITLEMENT', 'RELATIONSHIP', 'ENTITLEMENT_AND_RELATIONSHIP',
           'SUPPORT_SESSION']::VARCHAR[]),
    ('AUTH.PRODUCT_SURFACE.RESPONSIBILITY_REQUIREMENT',
     'dwp-auth-server', 'ProductSurfaceAuthorityDtos.ResponsibilityRequirement',
     'SECURITY', TRUE,
     ARRAY['REQUIRED', 'NOT_REQUIRED', 'LEGACY_OVERSIGHT']::VARCHAR[]),
    ('AUTH.ACCESS_REVIEW.PREDICATE_STATE',
     'dwp-auth-server', 'AccessReviewWorkService.PredicateState',
     'STATE_MACHINE', TRUE,
     ARRAY['ALLOWED', 'NOT_AVAILABLE', 'STALE_VERSION',
           'ALREADY_DECIDED']::VARCHAR[]),
    ('AUTH.OIDC_STATE.PURPOSE',
     'dwp-auth-server', 'OidcStateStore.Purpose',
     'SECURITY', TRUE,
     ARRAY['LOGIN', 'STEP_UP']::VARCHAR[]),
    ('GATEWAY.PRODUCT_ROUTE.MATCH_STATUS',
     'dwp-gateway', 'GeneratedProductRouteCatalog.MatchStatus',
     'SECURITY', TRUE,
     ARRAY['GOVERNED', 'LEGACY_EXEMPT', 'UNGOVERNED', 'INVALID',
           'AMBIGUOUS']::VARCHAR[]),
    ('GATEWAY.PRODUCT_SURFACE.AUTHORITY_STATUS',
     'dwp-gateway', 'ProductSurfaceContextDtos.AuthorityStatus',
     'STATE_MACHINE', TRUE,
     ARRAY['NOT_EVALUATED', 'AVAILABLE', 'UNAVAILABLE']::VARCHAR[]),
    ('GATEWAY.PRODUCT_SURFACE.FORWARDING_ENDPOINT',
     'dwp-gateway', 'ProductSurfaceForwardingGuardFilter.Endpoint',
     'SECURITY', TRUE,
     ARRAY['CONTEXTS', 'PRODUCT_EVALUATION',
           'GOVERNED_EVALUATION']::VARCHAR[]),
    ('GATEWAY.PRODUCT_SURFACE.ROLLOUT_APPROVAL_STATUS',
     'dwp-gateway', 'ProductSurfaceRolloutSafetyLatch.ApprovalStatus',
     'STATE_MACHINE', TRUE,
     ARRAY['CREATED', 'UPDATED', 'UNCHANGED', 'OUT_OF_ORDER',
           'REVISION_CONFLICT', 'INVALID_DECISION', 'CORRUPT',
           'UNAVAILABLE']::VARCHAR[]),
    ('GATEWAY.PRODUCT_SURFACE.ROLLOUT_LOAD_STATUS',
     'dwp-gateway', 'ProductSurfaceRolloutSafetyLatch.LoadStatus',
     'STATE_MACHINE', TRUE,
     ARRAY['FOUND', 'MISSING', 'MIGRATION_REQUIRED', 'CORRUPT',
           'UNAVAILABLE']::VARCHAR[]),
    ('MEETING.VIDEO_MEETING.ACCESS_SCOPE',
     'dwp-meeting-server', 'VideoMeetingModels.AccessScope',
     'SECURITY', TRUE,
     ARRAY['INTERNAL', 'INVITED', 'PUBLIC_CODE']::VARCHAR[]),
    ('MEETING.VIDEO_MEETING.ATTENDANCE_STATE',
     'dwp-meeting-server', 'VideoMeetingModels.AttendanceState',
     'STATE_MACHINE', TRUE,
     ARRAY['INVITED', 'REQUESTED', 'ADMITTED', 'DENIED', 'JOINED',
           'LEFT']::VARCHAR[]),
    ('MEETING.VIDEO_MEETING.LIFECYCLE_STATE',
     'dwp-meeting-server', 'VideoMeetingModels.LifecycleState',
     'STATE_MACHINE', TRUE,
     ARRAY['DRAFT', 'SCHEDULED', 'LOBBY', 'LIVE', 'ENDED',
           'CANCELLED']::VARCHAR[]),
    ('MEETING.VIDEO_MEETING.PARTICIPANT_ROLE',
     'dwp-meeting-server', 'VideoMeetingModels.ParticipantRole',
     'SECURITY', TRUE,
     ARRAY['ORGANIZER', 'CO_HOST', 'PRESENTER', 'ATTENDEE',
           'GUEST']::VARCHAR[]),
    ('NOTIFICATION.CHANGE_CAUSE',
     'dwp-notification-server', 'NotificationChangeCause',
     'PROTOCOL', TRUE,
     ARRAY['MATERIALIZED', 'USER_TRIAGE', 'SYSTEM_RECONCILIATION',
           'TARGET_LIFECYCLE']::VARCHAR[]),
    ('PEOPLE.HR.DATA_BOUNDARY',
     'dwp-people-server', 'HrDtos.DataBoundary',
     'SECURITY', TRUE,
     ARRAY['TEAM', 'ORGANIZATION_SET', 'TEAM_AND_ORGANIZATION_SET',
           'TENANT']::VARCHAR[]),
    ('PEOPLE.PRODUCT_SURFACE.ELIGIBILITY_DECISION',
     'dwp-people-server', 'ProductSurfaceEligibilityDtos.Decision',
     'SECURITY', TRUE,
     ARRAY['ALLOWED', 'SURFACE_DENIED', 'SCOPE_INVALID',
           'AUTHORITY_UNAVAILABLE']::VARCHAR[]),
    ('PEOPLE.WORKFORCE_CANDIDATE.ELIGIBILITY',
     'dwp-people-server', 'WorkforceCandidateDtos.Eligibility',
     'PROTOCOL', TRUE,
     ARRAY['ELIGIBLE', 'INELIGIBLE']::VARCHAR[]),
    ('PLATFORM.CALENDAR.ACCESS_LEVEL',
     'dwp-platform-server', 'CalendarTypes.CalendarAccessLevel',
     'SECURITY', TRUE,
     ARRAY['OWNER', 'MANAGE', 'EDIT', 'VIEW_DETAILS', 'VIEW_FREE_BUSY',
           'EVENT_ATTENDEE', 'NONE']::VARCHAR[]),
    ('PLATFORM.CALENDAR.SOURCE_KIND',
     'dwp-platform-server', 'CalendarTypes.CalendarSourceKind',
     'PROTOCOL', TRUE,
     ARRAY['OWNED', 'COMPANY', 'SHARED', 'TEAM', 'RESOURCE']::VARCHAR[]),
    ('PLATFORM.CALENDAR.EVENT_DETAIL_LEVEL',
     'dwp-platform-server', 'CalendarTypes.EventDetailLevel',
     'SECURITY', TRUE,
     ARRAY['FULL', 'FREE_BUSY']::VARCHAR[]),
    ('PLATFORM.HOME_PREFERENCE.INTEGRITY_STATUS',
     'dwp-platform-server', 'HomePreferenceDtos.HomePreferenceIntegrityStatus',
     'STATE_MACHINE', TRUE,
     ARRAY['VALID', 'RECONCILED', 'RECOVERED']::VARCHAR[]),
    ('PLATFORM.PRODUCT_SURFACE_TELEMETRY.POLICY_KIND',
     'dwp-platform-server', 'ProductSurfaceTelemetryDtos.PolicyKind',
     'OBSERVABILITY', TRUE,
     ARRAY['READ_ONLY', 'UPSTREAM_LOCK', 'SEGREGATION_OF_DUTIES',
           'STEP_UP', 'SUPPORT', 'EXPIRY']::VARCHAR[]),
    ('PLATFORM.PRODUCT_SURFACE_TELEMETRY.REASON_CODE',
     'dwp-platform-server', 'ProductSurfaceTelemetryDtos.ReasonCode',
     'OBSERVABILITY', TRUE,
     ARRAY['APP_DENIED', 'SURFACE_DENIED', 'ROUTE_DENIED',
           'SCOPE_SELECTION_REQUIRED', 'SCOPE_INVALID', 'EXPIRED',
           'ACTIVATION_REQUIRED', 'STEP_UP_REQUIRED', 'SOD_CONFLICT',
           'SUPPORT_SCOPE_DENIED', 'AUTHORITY_UNAVAILABLE', 'NETWORK_ERROR',
           'CANCELLED', 'VALIDATION_ERROR']::VARCHAR[]),
    ('PLATFORM.PRODUCT_SURFACE_TELEMETRY.TASK_KIND',
     'dwp-platform-server', 'ProductSurfaceTelemetryDtos.TaskKind',
     'OBSERVABILITY', TRUE,
     ARRAY['WORK', 'OPERATIONS', 'CONFIGURATION', 'ADMINISTRATION',
           'GOVERNANCE', 'DESIGN', 'INTEGRATION', 'REPORTING',
           'REVIEW']::VARCHAR[]),
    ('PLATFORM.APPROVALS.AUTHORIZATION_MODE',
     'dwp-platform-server', 'PlatformApprovalsAuthorizationContext.Mode',
     'SECURITY', TRUE,
     ARRAY['LEGACY', 'ENFORCED']::VARCHAR[]),
    ('PROVIDER.TENANT_MUTATION.COMPLETION',
     'dwp-provider-server', 'TenantMutationRepository.Completion',
     'STATE_MACHINE', TRUE,
     ARRAY['NOT_READY', 'SUCCEEDED', 'COMPENSATED',
           'RECONCILIATION_REQUIRED']::VARCHAR[]),
    ('PROVIDER.TENANT_MUTATION.FAILURE_DISPOSITION',
     'dwp-provider-server', 'TenantMutationRepository.FailureDisposition',
     'STATE_MACHINE', TRUE,
     ARRAY['RETRY_SCHEDULED', 'COMPENSATION_SCHEDULED',
           'RECONCILIATION_REQUIRED', 'LOST_LEASE']::VARCHAR[]),

    -- Existing database CHECK contracts are also the exact Java wire values.
    ('PLATFORM.CAL_CALENDARS.SUBSCRIPTION_POLICY',
     'dwp-platform-server', 'CalendarTypes.CalendarSubscriptionPolicy',
     'REFERENCE', FALSE,
     ARRAY['REQUIRED', 'DEFAULT_ON', 'OPTIONAL']::VARCHAR[]),
    ('PLATFORM.CAL_EVENTS.IMPORTANCE',
     'dwp-platform-server', 'CalendarTypes.EventImportance',
     'REFERENCE', FALSE,
     ARRAY['LOW', 'NORMAL', 'HIGH']::VARCHAR[]),
    ('PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.DEVICE_CLASS',
     'dwp-platform-server', 'ProductSurfaceTelemetryDtos.DeviceClass',
     'REFERENCE', FALSE,
     ARRAY['DESKTOP', 'TABLET', 'MOBILE']::VARCHAR[]),
    ('PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.ELAPSED_BUCKET',
     'dwp-platform-server', 'ProductSurfaceTelemetryDtos.ElapsedBucket',
     'REFERENCE', FALSE,
     ARRAY['LT_1S', 'S1_TO_5', 'S5_TO_15', 'S15_TO_30', 'S30_TO_60',
           'M1_TO_5', 'GTE_5M']::VARCHAR[]),
    ('PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.SCOPE_KIND',
     'dwp-platform-server', 'ProductSurfaceTelemetryDtos.ScopeKind',
     'REFERENCE', FALSE,
     ARRAY['TENANT', 'SELF', 'TEAM', 'ORG_UNIT', 'LEGAL_ENTITY', 'DOMAIN',
           'RESOURCE_SET', 'RESOURCE', 'POLICY_NODE', 'TARGET_POPULATION',
           'SUPPORT_SESSION']::VARCHAR[]);

CREATE TEMP TABLE tmp_v198_typed_binding_manifest (
    code_set_key VARCHAR(100) NOT NULL,
    consumer_service VARCHAR(80) NOT NULL,
    usage_type VARCHAR(30) NOT NULL,
    source_reference VARCHAR(300) NOT NULL,
    PRIMARY KEY (consumer_service, source_reference)
) ON COMMIT DROP;

INSERT INTO tmp_v198_typed_binding_manifest (
    code_set_key, consumer_service, usage_type, source_reference)
VALUES
    ('AUTH.PRODUCT_AUTHORIZATION.OPERATION_LANE', 'dwp-auth-server',
     'BEHAVIOR', 'ProductAuthorizationOperationsSecurityConfig.Lane'),
    ('AUTH.GOVERNED_ROUTE.DECISION', 'dwp-auth-server',
     'API_CONTRACT', 'GovernedRouteAuthorityDtos.Decision'),
    ('AUTH.PRODUCT_SURFACE.ACCESS_MODE', 'dwp-auth-server',
     'API_CONTRACT', 'ProductSurfaceAuthorityDtos.AccessMode'),
    ('AUTH.PRODUCT_SURFACE.ACCESS_SOURCE', 'dwp-auth-server',
     'API_CONTRACT', 'ProductSurfaceAuthorityDtos.AccessSource'),
    ('AUTH.PRODUCT_SURFACE.ACTIVATION_STATE', 'dwp-auth-server',
     'API_CONTRACT', 'ProductSurfaceAuthorityDtos.ActivationState'),
    ('AUTH.PRODUCT_SURFACE.CAPABILITY_AUTHORITY_MODE', 'dwp-auth-server',
     'API_CONTRACT', 'ProductSurfaceAuthorityDtos.CapabilityAuthorityMode'),
    ('AUTH.PRODUCT_SURFACE.DECISION', 'dwp-auth-server',
     'API_CONTRACT', 'ProductSurfaceAuthorityDtos.Decision'),
    ('AUTH.PRODUCT_SURFACE.POLICY_AUTHORITY_MODE', 'dwp-auth-server',
     'API_CONTRACT', 'ProductSurfaceAuthorityDtos.PolicyAuthorityMode'),
    ('AUTH.PRODUCT_SURFACE.RESPONSIBILITY_REQUIREMENT', 'dwp-auth-server',
     'API_CONTRACT', 'ProductSurfaceAuthorityDtos.ResponsibilityRequirement'),
    ('AUTH.ACCESS_REVIEW.PREDICATE_STATE', 'dwp-auth-server',
     'BEHAVIOR', 'AccessReviewWorkService.PredicateState'),
    ('AUTH.OIDC_STATE.PURPOSE', 'dwp-auth-server',
     'BEHAVIOR', 'OidcStateStore.Purpose'),

    ('GATEWAY.PRODUCT_ROUTE.MATCH_STATUS', 'dwp-gateway',
     'BEHAVIOR', 'GeneratedProductRouteCatalog.MatchStatus'),
    ('AUTH.PRODUCT_SURFACE.ACCESS_MODE', 'dwp-gateway',
     'API_CONTRACT', 'ProductSurfaceContextDtos.AccessMode'),
    ('AUTH.PRODUCT_SURFACE.ACCESS_SOURCE', 'dwp-gateway',
     'API_CONTRACT', 'ProductSurfaceContextDtos.AccessSource'),
    ('GATEWAY.PRODUCT_SURFACE.AUTHORITY_STATUS', 'dwp-gateway',
     'API_CONTRACT', 'ProductSurfaceContextDtos.AuthorityStatus'),
    ('AUTH.PRODUCT_SURFACE.CAPABILITY_AUTHORITY_MODE', 'dwp-gateway',
     'API_CONTRACT', 'ProductSurfaceContextDtos.CapabilityAuthorityMode'),
    ('AUTH.PRODUCT_SURFACE.DECISION', 'dwp-gateway',
     'API_CONTRACT', 'ProductSurfaceContextDtos.Decision'),
    ('AUTH.GOVERNED_ROUTE.DECISION', 'dwp-gateway',
     'API_CONTRACT', 'ProductSurfaceContextDtos.GovernedDecision'),
    ('AUTH.PRODUCT_SURFACE.POLICY_AUTHORITY_MODE', 'dwp-gateway',
     'API_CONTRACT', 'ProductSurfaceContextDtos.PolicyAuthorityMode'),
    ('GATEWAY.PRODUCT_SURFACE.FORWARDING_ENDPOINT', 'dwp-gateway',
     'BEHAVIOR', 'ProductSurfaceForwardingGuardFilter.Endpoint'),
    ('GATEWAY.PRODUCT_SURFACE.ROLLOUT_APPROVAL_STATUS', 'dwp-gateway',
     'BEHAVIOR', 'ProductSurfaceRolloutSafetyLatch.ApprovalStatus'),
    ('GATEWAY.PRODUCT_SURFACE.ROLLOUT_LOAD_STATUS', 'dwp-gateway',
     'BEHAVIOR', 'ProductSurfaceRolloutSafetyLatch.LoadStatus'),

    ('MEETING.VIDEO_MEETING.ACCESS_SCOPE', 'dwp-meeting-server',
     'API_CONTRACT', 'VideoMeetingModels.AccessScope'),
    ('MEETING.VIDEO_MEETING.ATTENDANCE_STATE', 'dwp-meeting-server',
     'API_CONTRACT', 'VideoMeetingModels.AttendanceState'),
    ('MEETING.VIDEO_MEETING.LIFECYCLE_STATE', 'dwp-meeting-server',
     'API_CONTRACT', 'VideoMeetingModels.LifecycleState'),
    ('MEETING.VIDEO_MEETING.PARTICIPANT_ROLE', 'dwp-meeting-server',
     'API_CONTRACT', 'VideoMeetingModels.ParticipantRole'),
    ('NOTIFICATION.CHANGE_CAUSE', 'dwp-notification-server',
     'BEHAVIOR', 'NotificationChangeCause'),

    ('PEOPLE.HR.DATA_BOUNDARY', 'dwp-people-server',
     'API_CONTRACT', 'HrDtos.DataBoundary'),
    ('AUTH.PRODUCT_SURFACE.ACCESS_MODE', 'dwp-people-server',
     'API_CONTRACT', 'ProductSurfaceEligibilityDtos.AccessMode'),
    ('PEOPLE.PRODUCT_SURFACE.ELIGIBILITY_DECISION', 'dwp-people-server',
     'API_CONTRACT', 'ProductSurfaceEligibilityDtos.Decision'),
    ('PEOPLE.WORKFORCE_CANDIDATE.ELIGIBILITY', 'dwp-people-server',
     'API_CONTRACT', 'WorkforceCandidateDtos.Eligibility'),

    ('PLATFORM.CALENDAR.ACCESS_LEVEL', 'dwp-platform-server',
     'API_CONTRACT', 'CalendarTypes.CalendarAccessLevel'),
    ('PLATFORM.CALENDAR.SOURCE_KIND', 'dwp-platform-server',
     'API_CONTRACT', 'CalendarTypes.CalendarSourceKind'),
    ('PLATFORM.CAL_CALENDARS.SUBSCRIPTION_POLICY', 'dwp-platform-server',
     'API_CONTRACT', 'CalendarTypes.CalendarSubscriptionPolicy'),
    ('PLATFORM.CALENDAR.EVENT_DETAIL_LEVEL', 'dwp-platform-server',
     'API_CONTRACT', 'CalendarTypes.EventDetailLevel'),
    ('PLATFORM.CAL_EVENTS.IMPORTANCE', 'dwp-platform-server',
     'API_CONTRACT', 'CalendarTypes.EventImportance'),
    ('PLATFORM.HOME_PREFERENCE.INTEGRITY_STATUS', 'dwp-platform-server',
     'API_CONTRACT', 'HomePreferenceDtos.HomePreferenceIntegrityStatus'),
    ('PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.DEVICE_CLASS',
     'dwp-platform-server', 'API_CONTRACT',
     'ProductSurfaceTelemetryDtos.DeviceClass'),
    ('PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.ELAPSED_BUCKET',
     'dwp-platform-server', 'API_CONTRACT',
     'ProductSurfaceTelemetryDtos.ElapsedBucket'),
    ('PLATFORM.PRODUCT_SURFACE_TELEMETRY.POLICY_KIND',
     'dwp-platform-server', 'API_CONTRACT',
     'ProductSurfaceTelemetryDtos.PolicyKind'),
    ('PLATFORM.PRODUCT_SURFACE_TELEMETRY.REASON_CODE',
     'dwp-platform-server', 'API_CONTRACT',
     'ProductSurfaceTelemetryDtos.ReasonCode'),
    ('PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.SCOPE_KIND',
     'dwp-platform-server', 'API_CONTRACT',
     'ProductSurfaceTelemetryDtos.ScopeKind'),
    ('PLATFORM.PRODUCT_SURFACE_TELEMETRY.TASK_KIND',
     'dwp-platform-server', 'API_CONTRACT',
     'ProductSurfaceTelemetryDtos.TaskKind'),
    ('PLATFORM.APPROVALS.AUTHORIZATION_MODE', 'dwp-platform-server',
     'BEHAVIOR', 'PlatformApprovalsAuthorizationContext.Mode'),

    ('PROVIDER.TENANT_MUTATION.COMPLETION', 'dwp-provider-server',
     'BEHAVIOR', 'TenantMutationRepository.Completion'),
    ('PROVIDER.TENANT_MUTATION.FAILURE_DISPOSITION', 'dwp-provider-server',
     'BEHAVIOR', 'TenantMutationRepository.FailureDisposition');

DO $v198_contract_guard$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v198_typed_contract_manifest manifest
          LEFT JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE NOT manifest.create_code_set
           AND code_set.code_set_key IS NULL
    ) THEN
        RAISE EXCEPTION
            'V198 expected a reusable code set that is not registered';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v198_typed_contract_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE manifest.create_code_set
           AND (code_set.owner_service <> manifest.owner_service
                OR code_set.source_reference <> manifest.source_reference
                OR code_set.validation_source <> 'TYPED_CONTRACT')
    ) THEN
        RAISE EXCEPTION
            'V198 code-set key is already owned by a different source';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v198_typed_binding_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = manifest.consumer_service
           AND binding.source_reference = manifest.source_reference
           AND binding.enforcement_type = 'TYPED_CONTRACT'
           AND binding.lifecycle_state = 'ACTIVE'
           AND binding.code_set_key <> manifest.code_set_key
    ) THEN
        RAISE EXCEPTION
            'V198 found a conflicting active typed-contract binding';
    END IF;
END;
$v198_contract_guard$;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT manifest.code_set_key,
       manifest.owner_service,
       manifest.source_reference,
       'Exact Java enum contract for ' || manifest.source_reference || '.',
       'SYSTEM',
       'TYPED_CONTRACT',
       manifest.source_reference,
       manifest.contract_kind,
       'ADMIN_ONLY',
       'ACTIVE'
  FROM tmp_v198_typed_contract_manifest manifest
 WHERE manifest.create_code_set
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    configuration_level = 'SYSTEM',
    validation_source = 'TYPED_CONTRACT',
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    runtime_visibility = 'ADMIN_ONLY',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE ROW(
          sys_code_sets.owner_service,
          sys_code_sets.display_name,
          sys_code_sets.description,
          sys_code_sets.configuration_level,
          sys_code_sets.validation_source,
          sys_code_sets.source_reference,
          sys_code_sets.contract_kind,
          sys_code_sets.runtime_visibility,
          sys_code_sets.lifecycle_state)
      IS DISTINCT FROM ROW(
          EXCLUDED.owner_service,
          EXCLUDED.display_name,
          EXCLUDED.description,
          'SYSTEM',
          'TYPED_CONTRACT',
          EXCLUDED.source_reference,
          EXCLUDED.contract_kind,
          'ADMIN_ONLY',
          'ACTIVE');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
SELECT manifest.code_set_key,
       value_ref.code,
       value_ref.code,
       jsonb_build_object('ko', value_ref.code, 'en', value_ref.code),
       '{}'::jsonb,
       value_ref.ordinality::INTEGER * 10,
       TRUE,
       'ACTIVE'
  FROM tmp_v198_typed_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE sys_code_values.lifecycle_state <> 'ACTIVE';

-- Preserve obsolete registry evidence while excluding it from the active
-- contract. Every allowed value above is copied from a current Java enum.
UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v198_typed_contract_manifest manifest
 WHERE code_value.code_set_key = manifest.code_set_key
   AND NOT (code_value.code = ANY (manifest.allowed_values))
   AND code_value.lifecycle_state <> 'RETIRED';

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key,
       manifest.consumer_service,
       manifest.usage_type,
       manifest.source_reference,
       'TYPED_CONTRACT',
       'ACTIVE'
  FROM tmp_v198_typed_binding_manifest manifest
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET
    enforcement_type = 'TYPED_CONTRACT',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE ROW(
          sys_code_bindings.enforcement_type,
          sys_code_bindings.lifecycle_state)
      IS DISTINCT FROM ROW('TYPED_CONTRACT', 'ACTIVE');
