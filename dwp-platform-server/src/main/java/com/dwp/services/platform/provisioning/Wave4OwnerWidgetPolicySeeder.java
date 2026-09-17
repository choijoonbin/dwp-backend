package com.dwp.services.platform.provisioning;

import org.springframework.jdbc.core.JdbcTemplate;

/** Keeps newly provisioned tenants aligned with the append-only Wave 4 Registry seed. */
final class Wave4OwnerWidgetPolicySeeder {

    private Wave4OwnerWidgetPolicySeeder() {
    }

    static void seed(JdbcTemplate jdbc, Long tenantId) {
        jdbc.update("""
                INSERT INTO adm_tenant_widget_policy_revisions (
                    policy_revision_id, tenant_id, definition_id, revision_number,
                    policy_state, enabled, selector_type, channel, version_id,
                    supported_surface_keys, audience_selector, required_widget,
                    locked_configuration, sharing_policy, impact_revision,
                    reason_code, reason_text, created_by)
                SELECT md5('wave4-owner-widget-policy:' || ? || ':' || definition_id)::uuid,
                       ?, definition_id, 1, 'PUBLISHED',
                       definition_key IN ('notification.app-badges', 'workplace.booking'),
                       'CHANNEL', 'STABLE', NULL,
                       '["workspace-home"]'::jsonb,
                       '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb,
                       FALSE, '{}'::jsonb, 'PRIVATE', NULL,
                       CASE WHEN definition_key = 'notification.app-badges'
                            THEN 'WAVE4_APP_BADGE_PROJECTION'
                            WHEN definition_key = 'workplace.booking'
                            THEN 'WAVE6_WORKPLACE_HOME_PROJECTION'
                            WHEN definition_key = 'dwaion.artifact'
                            THEN 'WAVE6_OWNER_PROVIDER_PENDING'
                            ELSE 'WAVE5_RENDERER_PENDING' END,
                       CASE WHEN definition_key = 'notification.app-badges'
                            THEN 'Wave 4 authoritative app badge projection'
                            WHEN definition_key = 'workplace.booking'
                            THEN 'Wave 6 recipient-bound Workplace booking projection'
                            WHEN definition_key = 'dwaion.artifact'
                            THEN 'DWAI.ON Home slot stays disabled until its owner provider is available'
                            ELSE 'Wave 4 owner provider registered; renderer activation deferred to Wave 5' END,
                       1
                  FROM plt_widget_definitions
                 WHERE definition_key IN (
                    'approval.focus-queue', 'approval.my-requests',
                    'meetings.next-prep', 'meetings.followup-candidates',
                    'notification.app-badges', 'notification.response-queue',
                    'space.change-feed', 'space.response-queue',
                    'messaging.response-queue', 'messaging.change-feed',
                    'hr.edu', 'hr.team-pulse',
                    'workplace.booking', 'dwaion.artifact')
                ON CONFLICT (tenant_id, definition_id, revision_number) DO NOTHING
                """, tenantId, tenantId);
        jdbc.update("""
                INSERT INTO adm_tenant_widget_policy_revisions (
                    policy_revision_id, tenant_id, definition_id, revision_number,
                    policy_state, enabled, selector_type, channel, version_id,
                    supported_surface_keys, audience_selector, required_widget,
                    locked_configuration, sharing_policy, impact_revision,
                    reason_code, reason_text, created_by)
                SELECT md5('dwaion-home-provider-v1.1-policy:' || ? )::uuid,
                       ?, definition_id, 2, 'PUBLISHED', TRUE,
                       'CHANNEL', 'STABLE', NULL,
                       '["workspace-home"]'::jsonb,
                       '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb,
                       FALSE, '{}'::jsonb, 'PRIVATE', NULL,
                       'DWAION_HOME_SIGNED_OWNER_PROVIDER',
                       'Recipient-bound DWAI-ON title projection with signed single-use transport',
                       1
                  FROM plt_widget_definitions
                 WHERE definition_key = 'dwaion.artifact'
                ON CONFLICT (tenant_id, definition_id, revision_number) DO NOTHING
                """, tenantId, tenantId);
        jdbc.update("""
                INSERT INTO adm_tenant_widget_policy_heads (
                    policy_head_id, tenant_id, definition_id, current_revision_id,
                    version, updated_by)
                SELECT md5('wave4-owner-widget-policy-head:' || ? || ':' || definition_id)::uuid,
                       ?, definition_id,
                       CASE WHEN definition_key = 'dwaion.artifact'
                            THEN md5('dwaion-home-provider-v1.1-policy:' || ? )::uuid
                            ELSE md5('wave4-owner-widget-policy:' || ? || ':' || definition_id)::uuid
                       END,
                       0, 1
                  FROM plt_widget_definitions
                 WHERE definition_key IN (
                    'approval.focus-queue', 'approval.my-requests',
                    'meetings.next-prep', 'meetings.followup-candidates',
                    'notification.app-badges', 'notification.response-queue',
                    'space.change-feed', 'space.response-queue',
                    'messaging.response-queue', 'messaging.change-feed',
                    'hr.edu', 'hr.team-pulse',
                    'workplace.booking', 'dwaion.artifact')
                ON CONFLICT (tenant_id, definition_id) DO NOTHING
                """, tenantId, tenantId, tenantId, tenantId);
        jdbc.update("""
                UPDATE adm_tenant_widget_policy_heads head
                   SET current_revision_id = md5(
                           'dwaion-home-provider-v1.1-policy:' || head.tenant_id)::uuid,
                       version = head.version + 1,
                       updated_by = 1,
                       updated_at = CURRENT_TIMESTAMP
                  FROM plt_widget_definitions definition
                 WHERE head.tenant_id = ?
                   AND head.definition_id = definition.definition_id
                   AND definition.definition_key = 'dwaion.artifact'
                   AND head.current_revision_id IS DISTINCT FROM md5(
                           'dwaion-home-provider-v1.1-policy:' || head.tenant_id)::uuid
                """, tenantId);
    }
}
