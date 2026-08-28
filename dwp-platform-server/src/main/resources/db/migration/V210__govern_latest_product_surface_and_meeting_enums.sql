-- Govern the Java enums added by the product-surface owner PEPs and Meeting
-- intelligence/lifecycle work. Existing CHECK and shared typed code sets are
-- reused; only vocabularies without a canonical set are created here.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp-platform:system-code-registry', 0));

LOCK TABLE sys_code_sets, sys_code_values, sys_code_bindings
    IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE tmp_v210_code_set_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    validation_source VARCHAR(30) NOT NULL,
    contract_kind VARCHAR(24) NOT NULL,
    create_code_set BOOLEAN NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    CONSTRAINT ck_tmp_v210_values_nonempty
        CHECK (cardinality(allowed_values) > 0)
) ON COMMIT DROP;

INSERT INTO tmp_v210_code_set_manifest VALUES
    ('AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND',
     'dwp-auth-server', 'auth_governed_route_contract.route_kind',
     'CHECK', 'REFERENCE', FALSE,
     ARRAY['ACTION', 'DATA', 'PAGE']::VARCHAR[]),
    ('AUTH.PRODUCT_SURFACE.ACCESS_MODE',
     'dwp-auth-server', 'ProductSurfaceAuthorityDtos.AccessMode',
     'TYPED_CONTRACT', 'SECURITY', FALSE,
     ARRAY['ELEVATED', 'NORMAL', 'PROVIDER_SUPPORT']::VARCHAR[]),

    ('MEETING.VM_MEETING_CONTENT_ACL.PERMISSION',
     'dwp-meeting-server', 'vm_meeting_content_acl.permission',
     'CHECK', 'SECURITY', FALSE,
     ARRAY['MANAGE', 'REVIEW', 'VIEW']::VARCHAR[]),
    ('MEETING.VM_MEETING_INTELLIGENCE_REPORTS.AUDIENCE',
     'dwp-meeting-server', 'vm_meeting_intelligence_reports.audience',
     'CHECK', 'SECURITY', FALSE,
     ARRAY['MEETING_PARTICIPANTS', 'PRIVATE_REVIEWERS']::VARCHAR[]),
    ('MEETING.VM_MEETING_INTELLIGENCE_REPORTS.REPORT_STATE',
     'dwp-meeting-server', 'vm_meeting_intelligence_reports.report_state',
     'CHECK', 'STATE_MACHINE', FALSE,
     ARRAY['APPROVED', 'DELETED', 'DRAFT', 'PUBLISHED',
           'REJECTED']::VARCHAR[]),
    ('MEETING.VM_MEETING_INTELLIGENCE_REVIEWS.DECISION',
     'dwp-meeting-server', 'vm_meeting_intelligence_reviews.decision',
     'CHECK', 'PROTOCOL', FALSE,
     ARRAY['APPROVE', 'REJECT']::VARCHAR[]),
    ('MEETING.VM_MEETING_INTELLIGENCE_RUNS.RUN_STATE',
     'dwp-meeting-server', 'vm_meeting_intelligence_runs.run_state',
     'CHECK', 'STATE_MACHINE', FALSE,
     ARRAY['FAILED', 'RUNNING', 'SUCCEEDED']::VARCHAR[]),
    ('MEETING.VM_MEETING_MEDIA_OPERATIONS.OPERATION_STATE',
     'dwp-meeting-server', 'vm_meeting_media_operations.operation_state',
     'CHECK', 'STATE_MACHINE', FALSE,
     ARRAY['FAILED', 'RUNNING', 'SUCCEEDED']::VARCHAR[]),
    ('MEETING.VM_MEETING_MEDIA_OPERATIONS.OPERATION_TYPE',
     'dwp-meeting-server', 'vm_meeting_media_operations.operation_type',
     'CHECK', 'PROTOCOL', FALSE,
     ARRAY['END', 'START']::VARCHAR[]),

    ('PLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS',
     'dwp-platform-server', 'CalendarProductSurfaceAccessPolicy.Status',
     'TYPED_CONTRACT', 'SECURITY', TRUE,
     ARRAY['ALLOWED', 'DENIED', 'UNAVAILABLE']::VARCHAR[]),
    ('PLATFORM.PRODUCT_SURFACE.ACCESS_CONTRACT_TYPE',
     'dwp-platform-server', 'CalendarProductSurfaceContract.AccessContractType',
     'TYPED_CONTRACT', 'SECURITY', TRUE,
     ARRAY['CAPABILITY', 'POLICY']::VARCHAR[]),
    ('MEETING.INTELLIGENCE.CLIMATE_LABEL',
     'dwp-meeting-server', 'MeetingIntelligenceProvider.ClimateLabel',
     'TYPED_CONTRACT', 'PROTOCOL', TRUE,
     ARRAY['ALIGNED', 'CONTESTED', 'INSUFFICIENT_EVIDENCE',
           'MIXED']::VARCHAR[]),
    ('MEETING.INTELLIGENCE.CLIMATE_SIGNAL',
     'dwp-meeting-server', 'MeetingIntelligenceProvider.ClimateSignal',
     'TYPED_CONTRACT', 'PROTOCOL', TRUE,
     ARRAY['BALANCED_TURN_TAKING', 'CONSTRUCTIVE_DISAGREEMENT',
           'DOMINANT_MONOLOGUE_PATTERN', 'LOW_TRANSCRIPT_EVIDENCE',
           'UNRESOLVED_DISAGREEMENT']::VARCHAR[]);

CREATE TEMP TABLE tmp_v210_typed_binding_manifest (
    code_set_key VARCHAR(100) NOT NULL,
    consumer_service VARCHAR(80) NOT NULL,
    usage_type VARCHAR(30) NOT NULL,
    source_reference VARCHAR(300) NOT NULL,
    PRIMARY KEY (
        code_set_key, consumer_service, usage_type, source_reference),
    UNIQUE (consumer_service, source_reference)
) ON COMMIT DROP;

INSERT INTO tmp_v210_typed_binding_manifest VALUES
    ('AUTH.PRODUCT_SURFACE.ACCESS_MODE', 'dwp-meeting-server',
     'API_CONTRACT', 'MeetingProductAccessPolicy.ActiveAccessMode'),
    ('AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND', 'dwp-meeting-server',
     'API_CONTRACT', 'MeetingProductAccessPolicy.RouteKind'),
    ('MEETING.VM_MEETING_INTELLIGENCE_REPORTS.AUDIENCE',
     'dwp-meeting-server', 'API_CONTRACT',
     'VideoMeetingIntelligenceModels.Audience'),
    ('MEETING.VM_MEETING_CONTENT_ACL.PERMISSION',
     'dwp-meeting-server', 'API_CONTRACT',
     'VideoMeetingIntelligenceModels.ContentPermission'),
    ('MEETING.VM_MEETING_INTELLIGENCE_REPORTS.REPORT_STATE',
     'dwp-meeting-server', 'API_CONTRACT',
     'VideoMeetingIntelligenceModels.ReportState'),
    ('MEETING.VM_MEETING_INTELLIGENCE_REVIEWS.DECISION',
     'dwp-meeting-server', 'API_CONTRACT',
     'VideoMeetingIntelligenceModels.ReviewDecision'),
    ('MEETING.VM_MEETING_INTELLIGENCE_RUNS.RUN_STATE',
     'dwp-meeting-server', 'API_CONTRACT',
     'VideoMeetingIntelligenceModels.RunState'),
    ('MEETING.VM_MEETING_MEDIA_OPERATIONS.OPERATION_STATE',
     'dwp-meeting-server', 'API_CONTRACT',
     'VideoMeetingLifecycleModels.OperationState'),
    ('MEETING.VM_MEETING_MEDIA_OPERATIONS.OPERATION_TYPE',
     'dwp-meeting-server', 'API_CONTRACT',
     'VideoMeetingLifecycleModels.OperationType'),
    ('MEETING.INTELLIGENCE.CLIMATE_LABEL', 'dwp-meeting-server',
     'API_CONTRACT', 'MeetingIntelligenceProvider.ClimateLabel'),
    ('MEETING.INTELLIGENCE.CLIMATE_SIGNAL', 'dwp-meeting-server',
     'API_CONTRACT', 'MeetingIntelligenceProvider.ClimateSignal'),

    ('PLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS',
     'dwp-platform-server', 'API_CONTRACT',
     'CalendarProductSurfaceAccessPolicy.Status'),
    ('PLATFORM.PRODUCT_SURFACE.ACCESS_CONTRACT_TYPE',
     'dwp-platform-server', 'API_CONTRACT',
     'CalendarProductSurfaceContract.AccessContractType'),
    ('AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND',
     'dwp-platform-server', 'API_CONTRACT',
     'CalendarProductSurfaceContract.RouteKind'),
    ('PLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS',
     'dwp-platform-server', 'API_CONTRACT',
     'CommunicationProductSurfacePepFilter.Decision'),
    ('AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND',
     'dwp-platform-server', 'API_CONTRACT',
     'CommunicationProductSurfacePepFilter.RouteKind'),
    ('PLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS',
     'dwp-platform-server', 'API_CONTRACT',
     'MailProductSurfaceAccessPolicy.Status'),
    ('PLATFORM.PRODUCT_SURFACE.ACCESS_CONTRACT_TYPE',
     'dwp-platform-server', 'API_CONTRACT',
     'MailProductSurfaceContract.AccessContractType'),
    ('AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND',
     'dwp-platform-server', 'API_CONTRACT',
     'MailProductSurfaceContract.RouteKind'),
    ('PLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS',
     'dwp-platform-server', 'API_CONTRACT',
     'ServicesProductSurfacePepFilter.Decision'),
    ('AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND',
     'dwp-platform-server', 'API_CONTRACT',
     'ServicesProductSurfacePepFilter.RouteKind');

DO $v210_preflight$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v210_code_set_manifest manifest
          LEFT JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE NOT manifest.create_code_set
           AND code_set.code_set_key IS NULL
    ) THEN
        RAISE EXCEPTION 'V210 requires an existing reusable code set';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v210_code_set_manifest manifest
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
          FROM tmp_v210_code_set_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.owner_service = manifest.owner_service
           AND code_set.source_reference = manifest.source_reference
         WHERE code_set.code_set_key <> manifest.code_set_key
           AND code_set.lifecycle_state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION
            'V210 code-set ownership, source, or canonical metadata drifted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v210_code_set_manifest manifest
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
        RAISE EXCEPTION 'V210 reusable or existing code-set values drifted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v210_typed_binding_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = manifest.consumer_service
           AND binding.source_reference = manifest.source_reference
           AND binding.lifecycle_state = 'ACTIVE'
         WHERE ROW(binding.code_set_key, binding.usage_type,
                   binding.enforcement_type)
               IS DISTINCT FROM
               ROW(manifest.code_set_key, manifest.usage_type,
                   'TYPED_CONTRACT')
    ) THEN
        RAISE EXCEPTION 'V210 found a conflicting typed-contract binding';
    END IF;
END;
$v210_preflight$;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT manifest.code_set_key, manifest.owner_service,
       manifest.source_reference,
       'Exact Java enum contract for ' || manifest.source_reference || '.',
       'SYSTEM', manifest.validation_source, manifest.source_reference,
       manifest.contract_kind, 'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v210_code_set_manifest manifest
 WHERE manifest.create_code_set
ON CONFLICT (code_set_key) DO NOTHING;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
SELECT manifest.code_set_key, value_ref.code, value_ref.code,
       jsonb_build_object('ko', value_ref.code, 'en', value_ref.code),
       '{}'::jsonb, value_ref.ordinality::INTEGER * 10, TRUE, 'ACTIVE'
  FROM tmp_v210_code_set_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
 WHERE manifest.create_code_set
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key, manifest.consumer_service, manifest.usage_type,
       manifest.source_reference, 'TYPED_CONTRACT', 'ACTIVE'
  FROM tmp_v210_typed_binding_manifest manifest
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO NOTHING;

DO $v210_postcondition$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v210_code_set_manifest manifest
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
          FROM tmp_v210_typed_binding_manifest manifest
         WHERE (SELECT COUNT(*)
                  FROM sys_code_bindings binding
                 WHERE binding.code_set_key = manifest.code_set_key
                   AND binding.consumer_service = manifest.consumer_service
                   AND binding.usage_type = manifest.usage_type
                   AND binding.source_reference = manifest.source_reference
                   AND binding.enforcement_type = 'TYPED_CONTRACT'
                   AND binding.lifecycle_state = 'ACTIVE') <> 1
    ) THEN
        RAISE EXCEPTION 'V210 typed code-contract convergence failed';
    END IF;
END;
$v210_postcondition$;
