-- Reconcile the central code registry with the closed enum-like CHECK constraints
-- that are already enforced by the service databases. The database constraints are
-- the authority for this forward-only registry projection; no service constraint is
-- widened or rewritten here.

CREATE TEMP TABLE tmp_v190_check_contract_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    UNIQUE (owner_service, source_reference),
    CONSTRAINT ck_tmp_v190_allowed_values_nonempty
        CHECK (cardinality(allowed_values) > 0)
) ON COMMIT DROP;

INSERT INTO tmp_v190_check_contract_manifest (
    code_set_key, owner_service, source_reference, allowed_values)
VALUES
    ('APPROVAL.APR_HIGH_RISK_IDEMPOTENCY_LEDGER.STATUS',
     'dwp-approval-server', 'apr_high_risk_idempotency_ledger.status',
     ARRAY['COMMITTED', 'IN_PROGRESS']::VARCHAR[]),
    ('APPROVAL.APR_INTEGRATION_OUTBOX.RECOVERY_AUDITOR_ASSIGNMENT_STATE',
     'dwp-approval-server', 'apr_integration_outbox.recovery_auditor_assignment_state',
     ARRAY['ASSIGNED', 'ASSIGNING', 'EXHAUSTED', 'LEGACY_UNASSIGNED',
           'NOT_REQUIRED', 'PENDING', 'RETRY']::VARCHAR[]),
    ('APPROVAL.APR_MANAGEMENT_SCOPE_SCHEMA_FENCE.ACTIVATED_BY_RELEASE',
     'dwp-approval-server', 'apr_management_scope_schema_fence.activated_by_release',
     ARRAY['approval-management-scope-v1']::VARCHAR[]),
    ('APPROVAL.APR_MANAGEMENT_SCOPE_SCHEMA_FENCE.FENCE_KEY',
     'dwp-approval-server', 'apr_management_scope_schema_fence.fence_key',
     ARRAY['APPROVAL_MANAGEMENT_SCOPE_V1']::VARCHAR[]),
    ('APPROVAL.APR_RECOVERY_AUDITOR_ASSIGNMENT_EVENTS.EVENT_TYPE',
     'dwp-approval-server', 'apr_recovery_auditor_assignment_events.event_type',
     ARRAY['AUTOMATIC_PROBE_EPOCH_OPENED', 'EPOCH_EXHAUSTED']::VARCHAR[]),
    ('APPROVAL.APR_STEP_UP_REPLAY_LEDGER.COMMAND_METHOD',
     'dwp-approval-server', 'apr_step_up_replay_ledger.command_method',
     ARRAY['DELETE', 'PATCH', 'POST', 'PUT']::VARCHAR[]),

    ('AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.LIFECYCLE_STATE',
     'dwp-auth-server', 'auth_governed_route_contract.lifecycle_state',
     ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND',
     'dwp-auth-server', 'auth_governed_route_contract.route_kind',
     ARRAY['ACTION', 'DATA', 'PAGE']::VARCHAR[]),
    ('AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.SUBJECT_TYPE',
     'dwp-auth-server', 'auth_governed_route_contract.subject_type',
     ARRAY['GOVERNED_CONTEXT', 'PRODUCT']::VARCHAR[]),
    ('AUTH.AUTH_PRODUCT_ACCESS_POLICY.LIFECYCLE_STATE',
     'dwp-auth-server', 'auth_product_access_policy.lifecycle_state',
     ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('AUTH.AUTH_PRODUCT_AUTHORITY_ENDPOINT.SERVICE_KEY',
     'dwp-auth-server', 'auth_product_authority_endpoint.service_key',
     ARRAY['approval', 'auth', 'people', 'platform', 'provider']::VARCHAR[]),
    ('AUTH.AUTH_PRODUCT_AUTHORIZATION_ACTIVATION_EVENT.OPERATION',
     'dwp-auth-server', 'auth_product_authorization_activation_event.operation',
     ARRAY['ACTIVATE', 'ROLLBACK']::VARCHAR[]),
    ('AUTH.AUTH_PRODUCT_AUTHORIZATION_BUNDLE.BUNDLE_STATUS',
     'dwp-auth-server', 'auth_product_authorization_bundle.bundle_status',
     ARRAY['ACTIVE', 'APPROVED', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('AUTH.AUTH_PRODUCT_AUTHORIZATION_BUNDLE.CHECKSUM_ALGORITHM',
     'dwp-auth-server', 'auth_product_authorization_bundle.checksum_algorithm',
     ARRAY['SHA-256']::VARCHAR[]),
    ('AUTH.AUTH_PRODUCT_AUTHORIZATION_SEED_RELEASE.INTENDED_BUNDLE_STATUS',
     'dwp-auth-server', 'auth_product_authorization_seed_release.intended_bundle_status',
     ARRAY['DRAFT']::VARCHAR[]),
    ('AUTH.AUTH_PRODUCT_CAPABILITY_CONTRACT.LIFECYCLE_STATE',
     'dwp-auth-server', 'auth_product_capability_contract.lifecycle_state',
     ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('AUTH.AUTH_PRODUCT_ENTITLEMENT_EXPRESSION.LIFECYCLE_STATE',
     'dwp-auth-server', 'auth_product_entitlement_expression.lifecycle_state',
     ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('AUTH.AUTH_PRODUCT_PREDICATE_POLICY.LIFECYCLE_STATE',
     'dwp-auth-server', 'auth_product_predicate_policy.lifecycle_state',
     ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('AUTH.AUTH_PRODUCT_PREDICATE_POLICY.OWNER_SERVICE_KEY',
     'dwp-auth-server', 'auth_product_predicate_policy.owner_service_key',
     ARRAY['approval', 'auth', 'people', 'platform']::VARCHAR[]),
    ('AUTH.COM_ACCESS_REVIEW_ITEMS.REVIEWER_ASSIGNMENT_STATE',
     'dwp-auth-server', 'com_access_review_items.reviewer_assignment_state',
     ARRAY['ACTIVE', 'REVOKED']::VARCHAR[]),
    ('AUTH.COM_ADMIN_APP_PRESET_ASSIGNMENTS.ASSIGNMENT_SOURCE',
     'dwp-auth-server', 'com_admin_app_preset_assignments.assignment_source',
     ARRAY['IAM', 'MANUAL', 'MIGRATION', 'PROVISIONING']::VARCHAR[]),
    ('AUTH.COM_ADMIN_APP_PRESET_ASSIGNMENTS.LIFECYCLE_STATE',
     'dwp-auth-server', 'com_admin_app_preset_assignments.lifecycle_state',
     ARRAY['ACTIVE', 'APPROVED', 'DENIED', 'EXPIRED',
           'PENDING_APPROVAL', 'REVOKED']::VARCHAR[]),
    ('AUTH.COM_ADMIN_APP_PRESET_ASSIGNMENTS.PRINCIPAL_TYPE',
     'dwp-auth-server', 'com_admin_app_preset_assignments.principal_type',
     ARRAY['GROUP', 'USER']::VARCHAR[]),
    ('AUTH.COM_ADMIN_APP_PRESET_ASSIGNMENTS.REQUEST_CHANNEL',
     'dwp-auth-server', 'com_admin_app_preset_assignments.request_channel',
     ARRAY['GOVERNANCE', 'SELF_SERVICE']::VARCHAR[]),
    ('AUTH.SCOPED_ADMIN.ASSIGNMENT_LIFECYCLE_STATE',
     'dwp-auth-server', 'com_admin_role_assignments.lifecycle_state',
     ARRAY['ACTIVE', 'APPROVED', 'DENIED', 'EXPIRED',
           'PENDING_APPROVAL', 'REVOKED']::VARCHAR[]),
    ('AUTH.COM_ADMIN_SCOPED_DUTY_ASSIGNMENTS.ASSIGNMENT_SOURCE',
     'dwp-auth-server', 'com_admin_scoped_duty_assignments.assignment_source',
     ARRAY['AGENT', 'GROUP', 'IAM', 'MANUAL', 'MIGRATION', 'PROVISIONING']::VARCHAR[]),
    ('AUTH.COM_ADMIN_SCOPED_DUTY_ASSIGNMENTS.LIFECYCLE_STATE',
     'dwp-auth-server', 'com_admin_scoped_duty_assignments.lifecycle_state',
     ARRAY['ACTIVE', 'APPROVED', 'DENIED', 'EXPIRED',
           'PENDING_APPROVAL', 'REVOKED']::VARCHAR[]),
    ('AUTH.COM_ADMIN_SCOPED_DUTY_ASSIGNMENTS.PRINCIPAL_TYPE',
     'dwp-auth-server', 'com_admin_scoped_duty_assignments.principal_type',
     ARRAY['GROUP', 'USER']::VARCHAR[]),
    ('AUTH.COM_ADMIN_SCOPED_DUTY_REVIEWS.LIFECYCLE_STATE',
     'dwp-auth-server', 'com_admin_scoped_duty_reviews.lifecycle_state',
     ARRAY['DISMISSED', 'OPEN', 'RESOLVED']::VARCHAR[]),
    ('AUTH.COM_USERS.IDENTITY_PLANE',
     'dwp-auth-server', 'com_users.identity_plane',
     ARRAY['PROVIDER', 'TENANT']::VARCHAR[]),
    ('AUTH.SYS_ADMIN_APP_PRESET_CATALOG.LIFECYCLE_STATE',
     'dwp-auth-server', 'sys_admin_app_preset_catalog.lifecycle_state',
     ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('AUTH.SYS_ADMIN_APP_PRESET_CATALOG.RISK_TIER',
     'dwp-auth-server', 'sys_admin_app_preset_catalog.risk_tier',
     ARRAY['HIGH', 'LOW', 'MEDIUM']::VARCHAR[]),
    ('AUTH.SYS_ADMIN_SCOPED_DUTY_CATALOG.LIFECYCLE_STATE',
     'dwp-auth-server', 'sys_admin_scoped_duty_catalog.lifecycle_state',
     ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('AUTH.SYS_ADMIN_SCOPED_DUTY_CATALOG.RISK_TIER',
     'dwp-auth-server', 'sys_admin_scoped_duty_catalog.risk_tier',
     ARRAY['HIGH', 'LOW', 'MEDIUM']::VARCHAR[]),
    ('AUTH.SYS_ADMIN_SCOPED_DUTY_CONFLICTS.LIFECYCLE_STATE',
     'dwp-auth-server', 'sys_admin_scoped_duty_conflicts.lifecycle_state',
     ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),

    ('NOTIFICATION.NTF_BULK_UNDO_ITEMS.BEFORE_INBOX_STATE',
     'dwp-notification-server', 'ntf_bulk_undo_items.before_inbox_state',
     ARRAY['ACTIVE', 'DONE']::VARCHAR[]),
    ('NOTIFICATION.NTF_BULK_UNDO_RECEIPTS.STATE',
     'dwp-notification-server', 'ntf_bulk_undo_receipts.state',
     ARRAY['AVAILABLE', 'COMPLETED']::VARCHAR[]),
    ('NOTIFICATION.NTF_USER_NOTIFICATIONS.TARGET_STATE',
     'dwp-notification-server', 'ntf_user_notifications.target_state',
     ARRAY['AVAILABLE', 'DELETED', 'FORBIDDEN']::VARCHAR[]),

    ('PEOPLE.PPL_STEP_UP_REPLAY_LEDGER.COMMAND_METHOD',
     'dwp-people-server', 'ppl_step_up_replay_ledger.command_method',
     ARRAY['DELETE', 'PATCH', 'POST', 'PUT']::VARCHAR[]),

    ('PLATFORM.ADM_HOME_TEMPLATE_REVISIONS.SOURCE',
     'dwp-platform-server', 'adm_home_template_revisions.source',
     ARRAY['CREATE', 'PUBLISH', 'REVOKE', 'UPDATE']::VARCHAR[]),
    ('PLATFORM.ADM_HOME_TEMPLATES.LIFECYCLE_STATE',
     'dwp-platform-server', 'adm_home_templates.lifecycle_state',
     ARRAY['DRAFT', 'PUBLISHED', 'REVOKED']::VARCHAR[]),
    ('PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.COHORT',
     'dwp-platform-server', 'plt_product_surface_ux_event.cohort',
     ARRAY['baseline', 'design-partner', 'eligible-10', 'eligible-25',
           'eligible-50', 'eligible-90', 'full', 'holdout', 'internal']::VARCHAR[]),
    ('PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.DEVICE_CLASS',
     'dwp-platform-server', 'plt_product_surface_ux_event.device_class',
     ARRAY['DESKTOP', 'MOBILE', 'TABLET']::VARCHAR[]),
    ('PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.ELAPSED_BUCKET',
     'dwp-platform-server', 'plt_product_surface_ux_event.elapsed_bucket',
     ARRAY['GTE_5M', 'LT_1S', 'M1_TO_5', 'S15_TO_30', 'S1_TO_5',
           'S30_TO_60', 'S5_TO_15']::VARCHAR[]),
    ('PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.EVENT_NAME',
     'dwp-platform-server', 'plt_product_surface_ux_event.event_name',
     ARRAY['surface.assignment.expired', 'surface.exposed',
           'surface.policy.lock.viewed', 'surface.returned',
           'surface.route.denied', 'surface.scope.invalid',
           'surface.scope.switch.completed', 'surface.scope.switch.failed',
           'surface.scope.switch.started', 'surface.switch.completed',
           'surface.switch.failed', 'surface.switch.started',
           'surface.task.abandoned', 'surface.task.completed',
           'surface.task.failed', 'surface.task.started']::VARCHAR[]),
    ('PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.SCOPE_KIND',
     'dwp-platform-server', 'plt_product_surface_ux_event.scope_kind',
     ARRAY['DOMAIN', 'LEGAL_ENTITY', 'ORG_UNIT', 'POLICY_NODE', 'RESOURCE',
           'RESOURCE_SET', 'SELF', 'SUPPORT_SESSION', 'TARGET_POPULATION',
           'TEAM', 'TENANT']::VARCHAR[]),
    ('PLATFORM.USR_HOME_COMPOSER_PROPOSALS.STATE',
     'dwp-platform-server', 'usr_home_composer_proposals.state',
     ARRAY['APPLIED', 'CANCELLED', 'FAILED', 'PREVIEWED', 'UNDONE']::VARCHAR[]),
    ('PLATFORM.USR_HOME_VIEW_DEVICE_LAYOUTS.DEVICE_CLASS',
     'dwp-platform-server', 'usr_home_view_device_layouts.device_class',
     ARRAY['DESKTOP', 'MOBILE']::VARCHAR[]),
    ('PLATFORM.USR_HOME_VIEW_REVISIONS.SOURCE',
     'dwp-platform-server', 'usr_home_view_revisions.source',
     ARRAY['AI', 'RESTORE', 'TEMPLATE', 'UNDO', 'USER']::VARCHAR[]),
    ('PLATFORM.USR_HOME_VIEWS.INTEGRITY_STATE',
     'dwp-platform-server', 'usr_home_views.integrity_state',
     ARRAY['RECOVERY_REQUIRED', 'VALID']::VARCHAR[]),
    ('PLATFORM.WRK_ITEMS.DATA_CLASSIFICATION',
     'dwp-platform-server', 'wrk_items.data_classification',
     ARRAY['CONFIDENTIAL', 'INTERNAL', 'PUBLIC', 'RESTRICTED']::VARCHAR[]),

    ('PROVIDER.AUDIT_EVENT_CATEGORY',
     'dwp-provider-server', 'prv_audit_events.event_category',
     ARRAY['ADMINISTRATION', 'CHANGE_MANAGEMENT', 'COMMERCIAL_GOVERNANCE',
           'DATA_GOVERNANCE', 'FEATURE_ROLLOUT', 'PRIVILEGED_ACCESS',
           'SERVICE_HEALTH', 'TENANT_LIFECYCLE']::VARCHAR[]),
    ('PROVIDER.PRV_FEATURE_ROLLOUT_DECISION_OUTBOX.DELIVERY_STATUS',
     'dwp-provider-server', 'prv_feature_rollout_decision_outbox.delivery_status',
     ARRAY['DEAD', 'FAILED', 'PENDING', 'PUBLISHED', 'SENDING']::VARCHAR[]),
    ('PROVIDER.PRV_FEATURE_ROLLOUT_DECISION_OUTBOX.STATE',
     'dwp-provider-server', 'prv_feature_rollout_decision_outbox.state',
     ARRAY['ADVANCED', 'DISABLED', 'ENABLED', 'PAUSED',
           'RESUMED', 'ROLLED_BACK']::VARCHAR[]),
    ('PROVIDER.PRV_FEATURE_ROLLOUT_DECISION_OUTBOX.TENANT_SCOPE',
     'dwp-provider-server', 'prv_feature_rollout_decision_outbox.tenant_scope',
     ARRAY['ALL', 'EXACT']::VARCHAR[]);

-- A source column must map to one canonical registry set. Failing on a
-- concurrent alternate registration is safer than silently merging contracts.
DO $v190_binding_guard$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v190_check_contract_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = manifest.owner_service
           AND binding.usage_type = 'DATABASE_COLUMN'
           AND binding.source_reference = manifest.source_reference
           AND binding.enforcement_type = 'CHECK'
           AND binding.lifecycle_state = 'ACTIVE'
           AND binding.code_set_key <> manifest.code_set_key
    ) THEN
        RAISE EXCEPTION
            'V190 found a conflicting active CHECK binding for a manifest source';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v190_check_contract_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.owner_service <> manifest.owner_service
            OR code_set.source_reference <> manifest.source_reference
    ) THEN
        RAISE EXCEPTION
            'V190 code-set key is already owned by a different source';
    END IF;
END;
$v190_binding_guard$;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT manifest.code_set_key,
       manifest.owner_service,
       manifest.source_reference,
       'Closed database CHECK contract for ' || manifest.source_reference || '.',
       'SYSTEM', 'CHECK', manifest.source_reference, 'REFERENCE',
       'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v190_check_contract_manifest manifest
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    configuration_level = 'SYSTEM',
    validation_source = 'CHECK',
    source_reference = EXCLUDED.source_reference,
    runtime_visibility = 'ADMIN_ONLY',
    lifecycle_state = 'ACTIVE'
WHERE ROW(
          sys_code_sets.owner_service,
          sys_code_sets.configuration_level,
          sys_code_sets.validation_source,
          sys_code_sets.source_reference,
          sys_code_sets.runtime_visibility,
          sys_code_sets.lifecycle_state)
      IS DISTINCT FROM ROW(
          EXCLUDED.owner_service,
          'SYSTEM',
          'CHECK',
          EXCLUDED.source_reference,
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
  FROM tmp_v190_check_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE sys_code_values.lifecycle_state <> 'ACTIVE';

-- Registry-only values are historical evidence, so retire rather than delete.
UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v190_check_contract_manifest manifest
 WHERE code_value.code_set_key = manifest.code_set_key
   AND NOT (code_value.code = ANY (manifest.allowed_values))
   AND code_value.lifecycle_state <> 'RETIRED';

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key,
       manifest.owner_service,
       'DATABASE_COLUMN',
       manifest.source_reference,
       'CHECK',
       'ACTIVE'
  FROM tmp_v190_check_contract_manifest manifest
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET
    enforcement_type = 'CHECK',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE ROW(
          sys_code_bindings.enforcement_type,
          sys_code_bindings.lifecycle_state)
      IS DISTINCT FROM ROW('CHECK', 'ACTIVE');
