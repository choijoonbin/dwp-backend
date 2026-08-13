CREATE TEMP TABLE tmp_wave3_code_contract_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    contract_kind VARCHAR(24) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    source_references VARCHAR[] NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_wave3_code_contract_manifest (
    code_set_key, owner_service, display_name, description,
    contract_kind, allowed_values, source_references)
VALUES
    ('AUTH.ACCESS_SCOPE', 'dwp-auth-server',
     'Access scope', 'Shared authorization boundary for governed access grants.',
     'SECURITY', ARRAY['TENANT', 'ORG_UNIT', 'RESOURCE']::VARCHAR[], ARRAY[
        'com_active_privileged_grants.scope_type',
        'com_delegated_admin_scopes.scope_type',
        'com_privileged_access_requests.scope_type',
        'com_privileged_role_eligibilities.scope_type']::VARCHAR[]),
    ('AUTH.ACCESS_GRANT.LIFECYCLE_STATE', 'dwp-auth-server',
     'Access grant lifecycle', 'Lifecycle shared by delegated and eligible access grants.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'REVOKED', 'EXPIRED']::VARCHAR[], ARRAY[
        'com_delegated_admin_scopes.lifecycle_state',
        'com_privileged_role_eligibilities.lifecycle_state']::VARCHAR[]),
    ('AUTH.EMERGENCY_ACCESS_PRINCIPAL.LIFECYCLE_STATE', 'dwp-auth-server',
     'Emergency access principal lifecycle',
     'Availability lifecycle for registered emergency access principals.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'SUSPENDED', 'RETIRED']::VARCHAR[],
     ARRAY['com_emergency_access_principals.lifecycle_state']::VARCHAR[]),
    ('AUTH.PRIVILEGED_ACCESS.APPROVAL_DECISION', 'dwp-auth-server',
     'Privileged access approval decision',
     'Independent reviewer decision for a privileged access request.',
     'SECURITY', ARRAY['APPROVE', 'DENY']::VARCHAR[],
     ARRAY['com_privileged_access_approvals.decision']::VARCHAR[]),
    ('AUTH.PRIVILEGED_ACCESS.ACTIVATION_MODE', 'dwp-auth-server',
     'Privileged access activation mode',
     'Activation workflow enforced by a privileged access policy.',
     'SECURITY', ARRAY['DISABLED', 'SELF_SERVICE', 'APPROVAL']::VARCHAR[],
     ARRAY['com_privileged_access_policies.activation_mode']::VARCHAR[]),
    ('AUTH.PRIVILEGED_ACCESS.ASSURANCE_LEVEL', 'dwp-auth-server',
     'Privileged access assurance level',
     'Authentication assurance required by policy and captured by request evidence.',
     'SECURITY', ARRAY['SESSION', 'MFA', 'PHISHING_RESISTANT']::VARCHAR[], ARRAY[
        'com_privileged_access_policies.assurance_level',
        'com_privileged_access_requests.assurance_level']::VARCHAR[]),
    ('AUTH.PRIVILEGED_ACCESS.EMERGENCY_MODE', 'dwp-auth-server',
     'Privileged access emergency mode',
     'Emergency activation path allowed by a privileged access policy.',
     'SECURITY', ARRAY['DISABLED', 'REGISTERED_PRINCIPAL', 'DUAL_APPROVAL']::VARCHAR[],
     ARRAY['com_privileged_access_policies.emergency_mode']::VARCHAR[]),
    ('AUTH.PRIVILEGED_ACCESS.POLICY_LIFECYCLE_STATE', 'dwp-auth-server',
     'Privileged access policy lifecycle',
     'Availability lifecycle for a privileged access policy.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[],
     ARRAY['com_privileged_access_policies.lifecycle_state']::VARCHAR[]),
    ('AUTH.PRIVILEGED_ACCESS.REQUEST_LIFECYCLE_STATE', 'dwp-auth-server',
     'Privileged access request lifecycle',
     'Approval, activation, revocation, and expiry lifecycle for privileged access.',
     'STATE_MACHINE', ARRAY[
        'PENDING_APPROVAL', 'ACTIVE', 'DENIED', 'REVOKED', 'EXPIRED', 'CANCELLED'
     ]::VARCHAR[], ARRAY['com_privileged_access_requests.lifecycle_state']::VARCHAR[]),
    ('AUTH.PRIVILEGED_ACCESS.REQUEST_TYPE', 'dwp-auth-server',
     'Privileged access request type',
     'Standard just-in-time and emergency privileged access paths.',
     'SECURITY', ARRAY['JIT', 'EMERGENCY']::VARCHAR[],
     ARRAY['com_privileged_access_requests.request_type']::VARCHAR[]),
    ('AUTH.PRIVILEGED_ACCESS.PRINCIPAL_TYPE', 'dwp-auth-server',
     'Privileged access principal type',
     'Identity subject classes eligible for privileged roles.',
     'SECURITY', ARRAY['USER', 'GROUP']::VARCHAR[],
     ARRAY['com_privileged_role_eligibilities.principal_type']::VARCHAR[]),
    ('AUTH.ROLE_CONFLICT.ENFORCEMENT', 'dwp-auth-server',
     'Role conflict enforcement',
     'Control response when a separation-of-duty conflict is detected.',
     'SECURITY', ARRAY['DENY', 'REQUIRE_APPROVAL']::VARCHAR[],
     ARRAY['sys_role_conflict_policies.enforcement']::VARCHAR[]),
    ('AUTH.ROLE_CONFLICT.RISK_LEVEL', 'dwp-auth-server',
     'Role conflict risk level',
     'Risk severity assigned to a separation-of-duty conflict.',
     'SECURITY', ARRAY['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']::VARCHAR[],
     ARRAY['sys_role_conflict_policies.risk_level']::VARCHAR[]),

    ('PLATFORM.CATALOG_ASSURANCE.FINDING_CODE', 'dwp-platform-server',
     'Catalog assurance finding code',
     'Machine-evaluated catalog integrity condition.',
     'OBSERVABILITY', ARRAY[
        'OWNER_MISSING', 'ORPHAN_ASSET', 'DEPRECATION_IMPACT'
     ]::VARCHAR[], ARRAY['adm_catalog_assurance_findings.finding_code']::VARCHAR[]),
    ('PLATFORM.CATALOG_ASSURANCE.DECISION', 'dwp-platform-server',
     'Catalog assurance decision',
     'Finding lifecycle and immutable operator disposition decision.',
     'STATE_MACHINE', ARRAY[
        'OPEN', 'ACKNOWLEDGED', 'FALSE_POSITIVE', 'ACCEPTED_RISK', 'RESOLVED'
     ]::VARCHAR[], ARRAY[
        'adm_catalog_assurance_findings.lifecycle_state',
        'adm_catalog_finding_dispositions.decision']::VARCHAR[]),
    ('PLATFORM.CATALOG_ASSURANCE.SEVERITY', 'dwp-platform-server',
     'Catalog assurance severity',
     'Business impact severity for a catalog assurance finding.',
     'OBSERVABILITY', ARRAY['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']::VARCHAR[],
     ARRAY['adm_catalog_assurance_findings.severity']::VARCHAR[]),
    ('PLATFORM.CATALOG_ASSURANCE.ACTOR_TYPE', 'dwp-platform-server',
     'Catalog assurance actor type',
     'Human or automated actor that records a catalog finding disposition.',
     'SECURITY', ARRAY['USER', 'SYSTEM']::VARCHAR[],
     ARRAY['adm_catalog_finding_dispositions.actor_type']::VARCHAR[]),
    ('PLATFORM.LOCALIZATION.BUNDLE_LIFECYCLE_STATE', 'dwp-platform-server',
     'Localization bundle lifecycle',
     'Availability lifecycle for a tenant localization bundle.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[],
     ARRAY['adm_localization_bundles.lifecycle_state']::VARCHAR[]),
    ('PLATFORM.LOCALIZATION.DECISION', 'dwp-platform-server',
     'Localization revision decision',
     'Append-only workflow decision recorded for a localization revision.',
     'STATE_MACHINE', ARRAY[
        'SUBMITTED', 'APPROVED', 'REJECTED', 'PUBLISHED', 'RESTORED'
     ]::VARCHAR[], ARRAY['adm_localization_revision_decisions.decision']::VARCHAR[]),
    ('PLATFORM.MANAGED_PREFERENCE.LIFECYCLE_STATE', 'dwp-platform-server',
     'Managed preference lifecycle',
     'Availability lifecycle shared by managed preference policies and rules.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[], ARRAY[
        'adm_managed_preference_policies.lifecycle_state',
        'adm_managed_preference_rules.lifecycle_state']::VARCHAR[]),
    ('PLATFORM.MANAGED_PREFERENCE.OWNER_TYPE', 'dwp-platform-server',
     'Managed preference owner type',
     'Decision owner responsible for managed preference exceptions.',
     'SECURITY', ARRAY['ROLE', 'GROUP', 'USER', 'SERVICE_DESK']::VARCHAR[],
     ARRAY['adm_managed_preference_policies.owner_type']::VARCHAR[]),
    ('PLATFORM.AUDIT_POLICY.APPROVAL_LIFECYCLE_STATE', 'dwp-platform-server',
     'Audit policy approval lifecycle',
     'Independent approval lifecycle for an audit policy revision.',
     'STATE_MACHINE', ARRAY[
        'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED'
     ]::VARCHAR[], ARRAY['sys_audit_policy_approvals.lifecycle_state']::VARCHAR[]),
    ('PLATFORM.AUDIT_POLICY.REVISION_LIFECYCLE_STATE', 'dwp-platform-server',
     'Audit policy revision lifecycle',
     'Draft, approval, publication, and replacement lifecycle for audit policy revisions.',
     'STATE_MACHINE', ARRAY[
        'DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'REJECTED',
        'CANCELLED', 'SUPERSEDED'
     ]::VARCHAR[], ARRAY['sys_audit_policy_revisions.lifecycle_state']::VARCHAR[]),
    ('PLATFORM.CATALOG_ASSURANCE.RULE_LIFECYCLE_STATE', 'dwp-platform-server',
     'Catalog assurance rule lifecycle',
     'Immutable compatibility rule publication lifecycle.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'SUPERSEDED', 'RETIRED']::VARCHAR[],
     ARRAY['sys_catalog_compatibility_rules.lifecycle_state']::VARCHAR[]),
    ('PLATFORM.PREFERENCE_EXCEPTION.ACTOR_TYPE', 'dwp-platform-server',
     'Preference exception actor type',
     'Actor class recorded in immutable preference exception evidence.',
     'SECURITY', ARRAY['USER', 'ADMIN', 'SYSTEM']::VARCHAR[],
     ARRAY['usr_preference_exception_decisions.actor_type']::VARCHAR[]),
    ('PLATFORM.PREFERENCE.EXCEPTION_DECISION', 'dwp-platform-server',
     'Preference exception decision',
     'Terminal decision captured for a managed preference exception.',
     'STATE_MACHINE', ARRAY['APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED']::VARCHAR[],
     ARRAY['usr_preference_exception_decisions.decision']::VARCHAR[]),
    ('PLATFORM.SAVED_VIEW.CUSTODY_DISPOSITION', 'dwp-platform-server',
     'Saved view custody disposition',
     'Ownership action applied to saved views when a user leaves or moves.',
     'SECURITY', ARRAY['TRANSFER', 'RETAIN_ORPHANED']::VARCHAR[],
     ARRAY['usr_saved_view_transfer_batches.disposition']::VARCHAR[]),

    ('PROVIDER.DATA_POLICY.LIFECYCLE_STATE', 'dwp-provider-server',
     'Provider data policy lifecycle',
     'Availability lifecycle for a provider data governance policy.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[],
     ARRAY['prv_data_policies.lifecycle_state']::VARCHAR[]),
    ('PROVIDER.DATA_POLICY.POLICY_TYPE', 'dwp-provider-server',
     'Provider data policy type',
     'Data governance control family represented by a versioned policy.',
     'SECURITY', ARRAY[
        'CLASSIFICATION', 'MINIMIZATION', 'RESIDENCY', 'RETENTION',
        'DELETION', 'LEGAL_HOLD', 'RESTRICTED_FIELD', 'TENANT_RLS'
     ]::VARCHAR[], ARRAY['prv_data_policies.policy_type']::VARCHAR[]),
    ('PROVIDER.DATA_POLICY.SCOPE_TYPE', 'dwp-provider-server',
     'Provider data policy scope',
     'Global, database, or asset boundary targeted by a data policy.',
     'SECURITY', ARRAY['GLOBAL', 'DATABASE', 'ASSET']::VARCHAR[],
     ARRAY['prv_data_policies.scope_type']::VARCHAR[]),
    ('PROVIDER.APPROVAL.LIFECYCLE_STATE', 'dwp-provider-server',
     'Provider approval lifecycle',
     'Independent approval lifecycle shared by rollout and data policy decisions.',
     'STATE_MACHINE', ARRAY['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED']::VARCHAR[], ARRAY[
        'prv_data_policy_approvals.lifecycle_state',
        'prv_feature_rollout_approvals.lifecycle_state']::VARCHAR[]),
    ('PROVIDER.DATA_POLICY.REVISION_LIFECYCLE_STATE', 'dwp-provider-server',
     'Provider data policy revision lifecycle',
     'Draft, approval, activation, replacement, and rollback lifecycle for data policy revisions.',
     'STATE_MACHINE', ARRAY[
        'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'REJECTED',
        'SUPERSEDED', 'ROLLED_BACK', 'CANCELLED'
     ]::VARCHAR[], ARRAY['prv_data_policy_revisions.lifecycle_state']::VARCHAR[]),
    ('PROVIDER.FEATURE_EVALUATION.REASON_CODE', 'dwp-provider-server',
     'Feature evaluation reason',
     'Audited reason explaining a tenant feature evaluation result.',
     'OBSERVABILITY', ARRAY[
        'DEFAULT', 'TARGET_MISS', 'PERCENTAGE_EXCLUDED', 'ROLLOUT_MATCH'
     ]::VARCHAR[], ARRAY['prv_feature_evaluation_audit.reason_code']::VARCHAR[]),
    ('PROVIDER.FEATURE_FLAG.LIFECYCLE_STATE', 'dwp-provider-server',
     'Feature flag lifecycle',
     'Availability lifecycle for a provider-controlled feature flag.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'DEPRECATED', 'RETIRED']::VARCHAR[],
     ARRAY['prv_feature_flags.lifecycle_state']::VARCHAR[]),
    ('PROVIDER.RISK_TIER', 'dwp-provider-server',
     'Provider risk tier', 'Shared provider operation and governance risk classification.',
     'SECURITY', ARRAY['L1', 'L2', 'L3']::VARCHAR[], ARRAY[
        'prv_feature_flags.risk_tier',
        'prv_support_access_requests.risk_tier']::VARCHAR[]),
    ('PROVIDER.FEATURE_FLAG.VALUE_TYPE', 'dwp-provider-server',
     'Feature flag value type',
     'Typed value contract supported by provider feature flags.',
     'PROTOCOL', ARRAY['BOOLEAN', 'STRING', 'NUMBER', 'JSON']::VARCHAR[],
     ARRAY['prv_feature_flags.value_type']::VARCHAR[]),
    ('PROVIDER.FEATURE_ROLLOUT.REVISION_LIFECYCLE_STATE', 'dwp-provider-server',
     'Feature rollout revision lifecycle',
     'Approval, activation, observation, completion, and rollback lifecycle for rollouts.',
     'STATE_MACHINE', ARRAY[
        'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'PAUSED',
        'COMPLETED', 'REJECTED', 'ROLLED_BACK', 'CANCELLED'
     ]::VARCHAR[], ARRAY['prv_feature_rollout_revisions.lifecycle_state']::VARCHAR[]),
    ('PROVIDER.FEATURE_ROLLOUT.STRATEGY', 'dwp-provider-server',
     'Feature rollout strategy',
     'Exposure strategy used by a feature rollout revision.',
     'PROTOCOL', ARRAY['RING', 'PERCENTAGE', 'ALL_AT_ONCE']::VARCHAR[],
     ARRAY['prv_feature_rollout_revisions.strategy']::VARCHAR[]),
    ('PROVIDER.FEATURE_ROLLOUT.STAGE_LIFECYCLE_STATE', 'dwp-provider-server',
     'Feature rollout stage lifecycle',
     'Execution lifecycle for an individual rollout stage.',
     'STATE_MACHINE', ARRAY['PENDING', 'ACTIVE', 'COMPLETED', 'SKIPPED']::VARCHAR[],
     ARRAY['prv_feature_rollout_stages.lifecycle_state']::VARCHAR[]),
    ('PROVIDER.SUBSCRIPTION_RENEWAL.EXECUTION_STATE', 'dwp-provider-server',
     'Subscription renewal execution state',
     'Operational follow-through state for an approved subscription renewal.',
     'STATE_MACHINE', ARRAY[
        'NOT_STARTED', 'NOT_REQUIRED', 'COMPLETED', 'MANUAL_ACTION_REQUIRED'
     ]::VARCHAR[], ARRAY['prv_subscription_renewal_revisions.execution_state']::VARCHAR[]),
    ('PROVIDER.SUBSCRIPTION_RENEWAL.LIFECYCLE_STATE', 'dwp-provider-server',
     'Subscription renewal lifecycle',
     'Approval and publication lifecycle for a subscription renewal revision.',
     'STATE_MACHINE', ARRAY[
        'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'PUBLISHED', 'EXPIRED'
     ]::VARCHAR[], ARRAY['prv_subscription_renewal_revisions.lifecycle_state']::VARCHAR[]),
    ('PROVIDER.SUBSCRIPTION_RENEWAL.NOTIFICATION_STATE', 'dwp-provider-server',
     'Subscription renewal notification state',
     'External notification delivery state for a subscription renewal.',
     'STATE_MACHINE', ARRAY[
        'NOT_REQUIRED', 'DISABLED_PENDING_CONTRACT', 'QUEUED', 'SENT', 'FAILED'
     ]::VARCHAR[], ARRAY['prv_subscription_renewal_revisions.notification_state']::VARCHAR[]),
    ('PROVIDER.SUPPORT_ACCESS_MODE', 'dwp-provider-server',
     'Support access mode', 'Standard and emergency provider support access modes.',
     'SECURITY', ARRAY['STANDARD', 'BREAK_GLASS']::VARCHAR[],
     ARRAY['prv_support_access_requests.access_mode']::VARCHAR[]),
    ('PROVIDER.SUPPORT_REQUEST.LIFECYCLE_STATE', 'dwp-provider-server',
     'Support access request lifecycle',
     'Request, approval, activation, completion, review, and expiry lifecycle for support access.',
     'STATE_MACHINE', ARRAY[
        'PENDING', 'PENDING_APPROVAL', 'APPROVED', 'DENIED', 'ACTIVATED',
        'COMPLETED', 'REVIEWED', 'EXPIRED', 'CANCELLED'
     ]::VARCHAR[], ARRAY['prv_support_access_requests.lifecycle_state']::VARCHAR[]),
    ('PROVIDER.SUPPORT_REQUEST.POST_REVIEW_STATE', 'dwp-provider-server',
     'Support access post-review state',
     'Mandatory post-access review lifecycle for provider support sessions.',
     'STATE_MACHINE', ARRAY['PENDING', 'COMPLETED', 'NOT_REQUIRED']::VARCHAR[],
     ARRAY['prv_support_access_requests.post_review_state']::VARCHAR[]);

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
SELECT code_set_key, owner_service, display_name, description,
       'SYSTEM', 'CHECK', source_references[1], contract_kind
  FROM tmp_wave3_code_contract_manifest
ON CONFLICT (code_set_key) DO NOTHING;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata)
SELECT manifest.code_set_key,
       value.code,
       INITCAP(REPLACE(LOWER(value.code), '_', ' ')),
       jsonb_build_object(
           'ko', INITCAP(REPLACE(LOWER(value.code), '_', ' ')),
           'en', INITCAP(REPLACE(LOWER(value.code), '_', ' '))),
       value.ordinality * 10,
       '{}'::jsonb
  FROM tmp_wave3_code_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
      WITH ORDINALITY AS value(code, ordinality)
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type)
SELECT manifest.code_set_key,
       manifest.owner_service,
       'DATABASE_COLUMN',
       binding.source_reference,
       'CHECK'
  FROM tmp_wave3_code_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.source_references)
      AS binding(source_reference)
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference
) DO NOTHING;
