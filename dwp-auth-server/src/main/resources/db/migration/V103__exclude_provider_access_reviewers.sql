-- Access-review Work is a tenant data-plane surface. A provider identity in the
-- bootstrap Auth tenant must never become a named reviewer or retain a live
-- reviewer relationship merely because it has a com_users row there.

-- Preserve non-user actor attribution when these policy-enforcement events are
-- copied to the durable audit outbox. Earlier versions predated actor_type and
-- hard-coded every identity event as USER.
CREATE OR REPLACE FUNCTION sys_identity_audit_to_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO sys_audit_outbox (
        outbox_id, event_id, tenant_id, payload, status, attempt_count,
        available_at, created_at, updated_at)
    VALUES (
        gen_random_uuid(), NEW.audit_event_id, NEW.tenant_id,
        jsonb_build_object(
            'eventId', NEW.audit_event_id,
            'eventVersion', '1.0',
            'occurredAt', NEW.occurred_at,
            'tenantId', NEW.tenant_id,
            'category', CASE
                WHEN NEW.action LIKE 'provisioning.%' THEN 'PROVISIONING'
                WHEN NEW.action LIKE 'access.%' OR NEW.action LIKE 'identity.%'
                    THEN 'AUTHORIZATION'
                ELSE 'ADMIN_CHANGE'
            END,
            'action', NEW.action,
            'outcome', NEW.outcome,
            'severity', CASE WHEN NEW.outcome = 'DENIED' THEN 'HIGH' ELSE 'INFO' END,
            'riskScore', CASE WHEN NEW.outcome = 'DENIED' THEN 70 ELSE 15 END,
            'actorType', NEW.actor_type,
            'actorId', NEW.actor_id::TEXT,
            'actorRoles', '[]'::jsonb,
            'sourceService', 'dwp-auth-server',
            'sourceModule', 'identity-governance',
            'environment', COALESCE(current_setting('dwp.environment', TRUE), 'local'),
            'targetType', NEW.target_type,
            'targetId', NEW.target_id,
            'targetDisplayName', NEW.target_id,
            'correlationId', NEW.correlation_id,
            'reason', NEW.reason,
            'beforeState', CASE
                WHEN NEW.before_snapshot IS NULL THEN '{}'::jsonb
                ELSE NEW.before_snapshot::jsonb
            END,
            'afterState', CASE
                WHEN NEW.after_snapshot IS NULL THEN '{}'::jsonb
                ELSE NEW.after_snapshot::jsonb
            END,
            'metadata', jsonb_build_object('legacyAuditEventId', NEW.audit_event_id),
            'retentionClass', CASE
                WHEN NEW.outcome = 'DENIED' THEN 'EXTENDED'
                ELSE 'STANDARD'
            END),
        'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
    ON CONFLICT (event_id) DO NOTHING;
    RETURN NEW;
END;
$$;

WITH revoked_items AS (
    UPDATE com_access_review_items item
       SET reviewer_assignment_state = 'REVOKED',
           version = item.version + 1,
           updated_at = CURRENT_TIMESTAMP
      FROM com_users reviewer
     WHERE reviewer.tenant_id = item.tenant_id
       AND reviewer.user_id = item.reviewer_user_id
       AND reviewer.identity_plane = 'PROVIDER'
       AND item.reviewer_assignment_state = 'ACTIVE'
    RETURNING item.tenant_id, item.access_review_item_id
)
INSERT INTO sys_identity_audit_events (
    audit_event_id, tenant_id, actor_id, actor_type, action, target_type,
    target_id, correlation_id, outcome, reason, before_snapshot,
    after_snapshot, occurred_at)
SELECT gen_random_uuid(), revoked.tenant_id, NULL, 'SYSTEM',
       'identity.provider-access-review-assignment.revoked-by-policy',
       'ACCESS_REVIEW_ITEM', revoked.access_review_item_id::TEXT,
       'migration:V103', 'SUCCESS',
       'Provider identities are not eligible for tenant access-review work.',
       '{"reviewerAssignmentState":"ACTIVE","reviewerPlane":"PROVIDER"}',
       '{"reviewerAssignmentState":"REVOKED","reviewerPlane":"REDACTED"}',
       CURRENT_TIMESTAMP
  FROM revoked_items revoked;

WITH provider_campaigns AS (
    SELECT campaign.access_review_campaign_id, campaign.tenant_id,
           campaign.lifecycle_state AS previous_lifecycle_state
      FROM com_access_review_campaigns campaign
      JOIN com_users reviewer
        ON reviewer.tenant_id = campaign.tenant_id
       AND reviewer.user_id = campaign.reviewer_user_id
     WHERE reviewer.identity_plane = 'PROVIDER'
       AND campaign.reviewer_strategy = 'NAMED_REVIEWER'
       AND campaign.lifecycle_state IN ('DRAFT', 'ACTIVE')
), cancelled_campaigns AS (
    UPDATE com_access_review_campaigns campaign
       SET lifecycle_state = 'CANCELLED',
           version = campaign.version + 1,
           updated_at = CURRENT_TIMESTAMP
      FROM provider_campaigns affected
     WHERE affected.access_review_campaign_id = campaign.access_review_campaign_id
    RETURNING campaign.tenant_id, campaign.access_review_campaign_id,
              affected.previous_lifecycle_state
)
INSERT INTO sys_identity_audit_events (
    audit_event_id, tenant_id, actor_id, actor_type, action, target_type,
    target_id, correlation_id, outcome, reason, before_snapshot,
    after_snapshot, occurred_at)
SELECT gen_random_uuid(), cancelled.tenant_id, NULL, 'SYSTEM',
       'identity.provider-access-review-assignment.revoked-by-policy',
       'ACCESS_REVIEW_CAMPAIGN', cancelled.access_review_campaign_id::TEXT,
       'migration:V103', 'SUCCESS',
       'Provider identities are not eligible for tenant access-review work.',
       jsonb_build_object(
           'lifecycleState', cancelled.previous_lifecycle_state,
           'reviewerPlane', 'PROVIDER')::TEXT,
       '{"lifecycleState":"CANCELLED","reviewerPlane":"REDACTED"}',
       CURRENT_TIMESTAMP
  FROM cancelled_campaigns cancelled;

CREATE OR REPLACE FUNCTION sys_enforce_access_review_reviewer_tenant_plane()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    reviewer_plane VARCHAR(16);
BEGIN
    IF NEW.reviewer_user_id IS NULL THEN RETURN NEW; END IF;

    SELECT reviewer.identity_plane
      INTO reviewer_plane
      FROM com_users reviewer
     WHERE reviewer.tenant_id = NEW.tenant_id
       AND reviewer.user_id = NEW.reviewer_user_id;

    IF reviewer_plane IS NULL THEN
        RAISE EXCEPTION
            'Unknown access-review reviewer for tenant %, user %',
            NEW.tenant_id, NEW.reviewer_user_id
            USING ERRCODE = '23503';
    END IF;
    IF reviewer_plane <> 'TENANT' THEN
        RAISE EXCEPTION
            'Provider identities cannot be access-review reviewers for tenant %, user %',
            NEW.tenant_id, NEW.reviewer_user_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_access_review_campaign_reviewer_tenant_plane
BEFORE INSERT OR UPDATE OF tenant_id, reviewer_user_id
ON com_access_review_campaigns
FOR EACH ROW EXECUTE FUNCTION sys_enforce_access_review_reviewer_tenant_plane();

CREATE TRIGGER trg_access_review_item_reviewer_tenant_plane
BEFORE INSERT OR UPDATE OF tenant_id, reviewer_user_id
ON com_access_review_items
FOR EACH ROW EXECUTE FUNCTION sys_enforce_access_review_reviewer_tenant_plane();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM com_access_review_campaigns campaign
          JOIN com_users reviewer
            ON reviewer.tenant_id = campaign.tenant_id
           AND reviewer.user_id = campaign.reviewer_user_id
         WHERE reviewer.identity_plane = 'PROVIDER'
           AND campaign.reviewer_strategy = 'NAMED_REVIEWER'
           AND campaign.lifecycle_state IN ('DRAFT', 'ACTIVE')
    ) OR EXISTS (
        SELECT 1
          FROM com_access_review_items item
          JOIN com_users reviewer
            ON reviewer.tenant_id = item.tenant_id
           AND reviewer.user_id = item.reviewer_user_id
         WHERE reviewer.identity_plane = 'PROVIDER'
           AND item.reviewer_assignment_state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Active provider access-review assignment remains after cleanup';
    END IF;
END;
$$;
