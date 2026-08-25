-- Local-only CORE-006 pilot state. This location is opt-in from devctl and is
-- rejected by production readiness before the application context starts.
-- Runtime composition uses global S plus product-scoped E_p/U_p: the four exact
-- v3 products are 111 and the seven inventory-only products are 100. The legacy
-- global E row remains enabled only as compatibility history and is composition-inert.

CREATE TEMP TABLE tmp_core006_local_context (
    provider_tenant_id UUID PRIMARY KEY,
    auth_tenant_id BIGINT NOT NULL,
    tenant_key VARCHAR(80) NOT NULL,
    data_region VARCHAR(40) NOT NULL,
    requester_id BIGINT NOT NULL,
    approver_id BIGINT NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_core006_local_context (
    provider_tenant_id, auth_tenant_id, tenant_key, data_region,
    requester_id, approver_id)
SELECT tenant.provider_tenant_id, tenant.auth_tenant_id,
       tenant.tenant_key, tenant.data_region,
       requester.provider_operator_id, approver.provider_operator_id
  FROM prv_tenants tenant
  JOIN prv_operators requester
    ON requester.auth_tenant_id = tenant.auth_tenant_id
   AND requester.auth_user_id = 1
   AND requester.lifecycle_state = 'ACTIVE'
  JOIN prv_operators approver
    ON approver.auth_tenant_id = tenant.auth_tenant_id
   AND approver.auth_user_id = 900016
   AND approver.lifecycle_state = 'ACTIVE'
 WHERE tenant.provider_tenant_id = '00000000-0000-0000-0000-000000000001'
   AND tenant.auth_tenant_id = 1
   AND tenant.tenant_key = 'default'
   AND tenant.data_region = 'local'
   AND tenant.lifecycle_state = 'ACTIVE';

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM tmp_core006_local_context) <> 1 THEN
        RAISE EXCEPTION
            'CORE-006 local rollout seed requires the exact active local tenant and actors';
    END IF;
    IF EXISTS (
        SELECT 1 FROM tmp_core006_local_context
         WHERE requester_id = approver_id) THEN
        RAISE EXCEPTION
            'CORE-006 local rollout requester and approver must be independent operators';
    END IF;
END $$;

CREATE TEMP TABLE tmp_core006_local_rollouts (
    feature_key VARCHAR(160) PRIMARY KEY,
    rollout_revision_id UUID NOT NULL UNIQUE,
    rollout_stage_id UUID NOT NULL UNIQUE,
    rollout_approval_id UUID NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_core006_local_rollouts VALUES
    ('access.product-surfaces.context-shadow.v1',
     'c0061000-0000-4000-8000-000000000101',
     'c0062000-0000-4000-8000-000000000101',
     'c0063000-0000-4000-8000-000000000101', TRUE),
    ('access.product-surfaces.capability-enforcement.v1',
     'c0061000-0000-4000-8000-000000000102',
     'c0062000-0000-4000-8000-000000000102',
     'c0063000-0000-4000-8000-000000000102', TRUE),
    ('access.product-surfaces.capability-enforcement.approvals.v1',
     'c0061000-0000-4000-8000-000000000201',
     'c0062000-0000-4000-8000-000000000201',
     'c0063000-0000-4000-8000-000000000201', TRUE),
    ('access.product-surfaces.capability-enforcement.calendar.v1',
     'c0061000-0000-4000-8000-000000000202',
     'c0062000-0000-4000-8000-000000000202',
     'c0063000-0000-4000-8000-000000000202', FALSE),
    ('access.product-surfaces.capability-enforcement.communications.v1',
     'c0061000-0000-4000-8000-000000000203',
     'c0062000-0000-4000-8000-000000000203',
     'c0063000-0000-4000-8000-000000000203', TRUE),
    ('access.product-surfaces.capability-enforcement.dwaion.v1',
     'c0061000-0000-4000-8000-000000000204',
     'c0062000-0000-4000-8000-000000000204',
     'c0063000-0000-4000-8000-000000000204', FALSE),
    ('access.product-surfaces.capability-enforcement.hcm.v1',
     'c0061000-0000-4000-8000-000000000205',
     'c0062000-0000-4000-8000-000000000205',
     'c0063000-0000-4000-8000-000000000205', TRUE),
    ('access.product-surfaces.capability-enforcement.mail.v1',
     'c0061000-0000-4000-8000-000000000206',
     'c0062000-0000-4000-8000-000000000206',
     'c0063000-0000-4000-8000-000000000206', FALSE),
    ('access.product-surfaces.capability-enforcement.messaging.v1',
     'c0061000-0000-4000-8000-000000000207',
     'c0062000-0000-4000-8000-000000000207',
     'c0063000-0000-4000-8000-000000000207', FALSE),
    ('access.product-surfaces.capability-enforcement.notifications.v1',
     'c0061000-0000-4000-8000-000000000208',
     'c0062000-0000-4000-8000-000000000208',
     'c0063000-0000-4000-8000-000000000208', FALSE),
    ('access.product-surfaces.capability-enforcement.services.v1',
     'c0061000-0000-4000-8000-000000000209',
     'c0062000-0000-4000-8000-000000000209',
     'c0063000-0000-4000-8000-000000000209', TRUE),
    ('access.product-surfaces.capability-enforcement.spaces.v1',
     'c0061000-0000-4000-8000-000000000210',
     'c0062000-0000-4000-8000-000000000210',
     'c0063000-0000-4000-8000-000000000210', FALSE),
    ('access.product-surfaces.capability-enforcement.workplace.v1',
     'c0061000-0000-4000-8000-000000000211',
     'c0062000-0000-4000-8000-000000000211',
     'c0063000-0000-4000-8000-000000000211', FALSE),
    ('ux.product-surfaces.communications.v1',
     'c0061000-0000-4000-8000-000000000103',
     'c0062000-0000-4000-8000-000000000103',
     'c0063000-0000-4000-8000-000000000103', TRUE),
    ('ux.product-surfaces.services.v1',
     'c0061000-0000-4000-8000-000000000104',
     'c0062000-0000-4000-8000-000000000104',
     'c0063000-0000-4000-8000-000000000104', TRUE),
    ('ux.product-surfaces.approvals.v1',
     'c0061000-0000-4000-8000-000000000105',
     'c0062000-0000-4000-8000-000000000105',
     'c0063000-0000-4000-8000-000000000105', TRUE),
    ('ux.product-surfaces.hcm.v1',
     'c0061000-0000-4000-8000-000000000106',
     'c0062000-0000-4000-8000-000000000106',
     'c0063000-0000-4000-8000-000000000106', TRUE),
    ('ux.product-surfaces.dwaion.v1',
     'c0061000-0000-4000-8000-000000000107',
     'c0062000-0000-4000-8000-000000000107',
     'c0063000-0000-4000-8000-000000000107', FALSE),
    ('ux.product-surfaces.notifications.v1',
     'c0061000-0000-4000-8000-000000000108',
     'c0062000-0000-4000-8000-000000000108',
     'c0063000-0000-4000-8000-000000000108', FALSE),
    ('ux.product-surfaces.calendar.v1',
     'c0061000-0000-4000-8000-000000000109',
     'c0062000-0000-4000-8000-000000000109',
     'c0063000-0000-4000-8000-000000000109', FALSE),
    ('ux.product-surfaces.workplace.v1',
     'c0061000-0000-4000-8000-000000000110',
     'c0062000-0000-4000-8000-000000000110',
     'c0063000-0000-4000-8000-000000000110', FALSE),
    ('ux.product-surfaces.mail.v1',
     'c0061000-0000-4000-8000-000000000111',
     'c0062000-0000-4000-8000-000000000111',
     'c0063000-0000-4000-8000-000000000111', FALSE),
    ('ux.product-surfaces.messaging.v1',
     'c0061000-0000-4000-8000-000000000112',
     'c0062000-0000-4000-8000-000000000112',
     'c0063000-0000-4000-8000-000000000112', FALSE),
    ('ux.product-surfaces.spaces.v1',
     'c0061000-0000-4000-8000-000000000113',
     'c0062000-0000-4000-8000-000000000113',
     'c0063000-0000-4000-8000-000000000113', FALSE);

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM tmp_core006_local_rollouts) <> 24
       OR (SELECT COUNT(*) FROM tmp_core006_local_rollouts WHERE enabled) <> 10
       OR (SELECT COUNT(*) FROM tmp_core006_local_rollouts WHERE NOT enabled) <> 14 THEN
        RAISE EXCEPTION 'CORE-006 local rollout truth table must remain 10 enabled and 14 disabled';
    END IF;
    IF (SELECT COUNT(*)
          FROM prv_feature_flags flag
          JOIN tmp_core006_local_rollouts seed USING (feature_key)
         WHERE flag.lifecycle_state = 'ACTIVE'
           AND flag.value_type = 'BOOLEAN'
           AND flag.default_value = 'false'::jsonb) <> 24 THEN
        RAISE EXCEPTION
            'CORE-006 local rollout seed requires all 24 production defaults to remain false';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM prv_feature_rollout_revisions revision
          JOIN tmp_core006_local_rollouts seed
            ON seed.feature_key = (
                SELECT feature_key FROM prv_feature_flags
                 WHERE feature_flag_id = revision.feature_flag_id)
         WHERE revision.revision_number = 1
           AND revision.rollout_revision_id <> seed.rollout_revision_id) THEN
        RAISE EXCEPTION
            'CORE-006 local rollout revision 1 is already owned by a non-bootstrap revision';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tmp_core006_local_rollouts seed
          JOIN prv_feature_rollout_revisions revision
            ON revision.rollout_revision_id = seed.rollout_revision_id
          JOIN prv_feature_flags flag
            ON flag.feature_flag_id = revision.feature_flag_id
         WHERE flag.feature_key <> seed.feature_key
            OR revision.revision_number <> 1) THEN
        RAISE EXCEPTION
            'CORE-006 deterministic revision id is owned by another rollout';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tmp_core006_local_rollouts seed
          JOIN prv_feature_rollout_stages stage
            ON stage.rollout_stage_id = seed.rollout_stage_id
         WHERE stage.rollout_revision_id <> seed.rollout_revision_id) THEN
        RAISE EXCEPTION
            'CORE-006 deterministic stage id is owned by another rollout';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tmp_core006_local_rollouts seed
          JOIN prv_feature_rollout_approvals approval
            ON approval.rollout_approval_id = seed.rollout_approval_id
         WHERE approval.rollout_revision_id <> seed.rollout_revision_id) THEN
        RAISE EXCEPTION
            'CORE-006 deterministic approval id is owned by another rollout';
    END IF;
END $$;

-- A prior exact-local bootstrap revision is no longer effective. Broader or
-- differently targeted operator rollouts are never rewritten by this seed.
UPDATE prv_feature_rollout_revisions revision
   SET lifecycle_state = 'ROLLED_BACK',
       completed_at = COALESCE(revision.completed_at, CURRENT_TIMESTAMP),
       version = revision.version + 1,
       updated_at = CURRENT_TIMESTAMP
  FROM prv_feature_flags flag, tmp_core006_local_rollouts seed,
       tmp_core006_local_context context
 WHERE revision.feature_flag_id = flag.feature_flag_id
   AND flag.feature_key = seed.feature_key
   AND revision.rollout_revision_id <> seed.rollout_revision_id
   AND revision.lifecycle_state IN ('ACTIVE', 'PAUSED', 'COMPLETED')
   AND revision.targeting = jsonb_build_object(
       'tenantIds', jsonb_build_array(context.provider_tenant_id::text));

INSERT INTO prv_feature_rollout_revisions AS current (
    rollout_revision_id, feature_flag_id, revision_number, name,
    lifecycle_state, rollout_value, targeting, strategy,
    current_stage_order, justification, requested_by, approved_by,
    submitted_at, approved_at, activated_at, version)
SELECT seed.rollout_revision_id, flag.feature_flag_id, 1,
       'Local CORE-006 pilot', 'ACTIVE', to_jsonb(seed.enabled),
       jsonb_build_object(
           'tenantIds', jsonb_build_array(context.provider_tenant_id::text)),
       'ALL_AT_ONCE', 1,
       'Deterministic local-only CORE-006 pilot bootstrap.',
       context.requester_id, context.approver_id,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 3
  FROM tmp_core006_local_rollouts seed
  JOIN prv_feature_flags flag USING (feature_key)
 CROSS JOIN tmp_core006_local_context context
ON CONFLICT (rollout_revision_id) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    rollout_value = EXCLUDED.rollout_value,
    targeting = EXCLUDED.targeting,
    strategy = 'ALL_AT_ONCE',
    current_stage_order = 1,
    requested_by = EXCLUDED.requested_by,
    approved_by = EXCLUDED.approved_by,
    submitted_at = COALESCE(current.submitted_at, EXCLUDED.submitted_at),
    approved_at = COALESCE(current.approved_at, EXCLUDED.approved_at),
    activated_at = COALESCE(current.activated_at, EXCLUDED.activated_at),
    completed_at = NULL,
    paused_at = NULL,
    version = GREATEST(current.version, 3),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_feature_rollout_stages AS current (
    rollout_stage_id, rollout_revision_id, stage_order, stage_name,
    exposure_percentage, minimum_observation_minutes, health_gate,
    lifecycle_state, started_at)
SELECT seed.rollout_stage_id, seed.rollout_revision_id, 1,
       'Exact local tenant at 100 percent', 100.00, 0, '{}'::jsonb,
       'ACTIVE', CURRENT_TIMESTAMP
  FROM tmp_core006_local_rollouts seed
ON CONFLICT (rollout_stage_id) DO UPDATE SET
    rollout_revision_id = EXCLUDED.rollout_revision_id,
    stage_order = 1,
    stage_name = EXCLUDED.stage_name,
    exposure_percentage = 100.00,
    minimum_observation_minutes = 0,
    health_gate = '{}'::jsonb,
    lifecycle_state = 'ACTIVE',
    started_at = COALESCE(current.started_at, EXCLUDED.started_at),
    completed_at = NULL;

INSERT INTO prv_feature_rollout_approvals AS current (
    rollout_approval_id, rollout_revision_id, lifecycle_state,
    requested_by, requested_at, decided_by, decided_at, decision_reason)
SELECT seed.rollout_approval_id, seed.rollout_revision_id, 'APPROVED',
       context.requester_id, CURRENT_TIMESTAMP,
       context.approver_id, CURRENT_TIMESTAMP,
       'Independent local-only CORE-006 pilot approval.'
  FROM tmp_core006_local_rollouts seed
 CROSS JOIN tmp_core006_local_context context
ON CONFLICT (rollout_approval_id) DO UPDATE SET
    rollout_revision_id = EXCLUDED.rollout_revision_id,
    lifecycle_state = 'APPROVED',
    requested_by = EXCLUDED.requested_by,
    requested_at = COALESCE(current.requested_at, EXCLUDED.requested_at),
    decided_by = EXCLUDED.decided_by,
    decided_at = COALESCE(current.decided_at, EXCLUDED.decided_at),
    decision_reason = EXCLUDED.decision_reason;

UPDATE prv_feature_rollout_decision_revision decision
   SET opaque_revision = GREATEST(decision.opaque_revision, 1),
       updated_at = CURRENT_TIMESTAMP
  FROM prv_feature_flags flag
  JOIN tmp_core006_local_rollouts seed USING (feature_key)
 WHERE decision.feature_flag_id = flag.feature_flag_id;

DO $$
BEGIN
    IF (SELECT COUNT(*)
          FROM prv_feature_rollout_revisions revision
          JOIN tmp_core006_local_rollouts seed
            ON seed.rollout_revision_id = revision.rollout_revision_id
          JOIN prv_feature_flags flag
            ON flag.feature_flag_id = revision.feature_flag_id
           AND flag.feature_key = seed.feature_key
          JOIN tmp_core006_local_context context ON TRUE
         WHERE revision.lifecycle_state = 'ACTIVE'
           AND revision.revision_number = 1
           AND revision.version >= 3
           AND revision.current_stage_order = 1
           AND revision.requested_by <> revision.approved_by
           AND revision.targeting = jsonb_build_object(
               'tenantIds', jsonb_build_array(context.provider_tenant_id::text))
           AND revision.rollout_value = to_jsonb(seed.enabled)) <> 24 THEN
        RAISE EXCEPTION 'CORE-006 local rollout revisions are not exactly active';
    END IF;
    IF (SELECT COUNT(*)
          FROM prv_feature_rollout_stages stage
          JOIN tmp_core006_local_rollouts seed
            ON seed.rollout_stage_id = stage.rollout_stage_id
         WHERE stage.stage_order = 1
           AND stage.exposure_percentage = 100.00
           AND stage.lifecycle_state = 'ACTIVE') <> 24 THEN
        RAISE EXCEPTION 'CORE-006 local rollout stages are not active at 100 percent';
    END IF;
    IF (SELECT COUNT(*)
          FROM prv_feature_rollout_approvals approval
          JOIN tmp_core006_local_rollouts seed
            ON seed.rollout_approval_id = approval.rollout_approval_id
         WHERE approval.lifecycle_state = 'APPROVED'
           AND approval.requested_by <> approval.decided_by) <> 24 THEN
        RAISE EXCEPTION 'CORE-006 local rollout approvals violate maker-checker separation';
    END IF;
END $$;
