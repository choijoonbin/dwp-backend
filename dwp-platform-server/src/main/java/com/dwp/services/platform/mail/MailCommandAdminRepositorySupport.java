package com.dwp.services.platform.mail;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists tenant-administration mutations and their immutable audit evidence. */
final class MailCommandAdminRepositorySupport {

    private final JdbcTemplate jdbc;
    private final MailJsonCodec json;

    MailCommandAdminRepositorySupport(JdbcTemplate jdbc, MailJsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    int updatePolicy(Long tenantId, Long userId, MailDtos.TenantPolicyRequest request) {
        return jdbc.update("""
                UPDATE mail_tenant_policies
                   SET external_sender_banner = ?, block_remote_images = ?,
                       allow_shared_inboxes = ?, ai_assistance_enabled = ?,
                       ai_cross_app_actions_enabled = ?, retention_days = ?,
                       maximum_attachment_mb = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND version = ?
                """, request.externalSenderBanner(), request.blockRemoteImages(),
                request.allowSharedInboxes(), request.aiAssistanceEnabled(),
                request.aiCrossAppActionsEnabled(), request.retentionDays(),
                request.maximumAttachmentMb(), userId, tenantId, request.version());
    }

    void policyHistory(
            Long tenantId, Long userId, long policyVersion,
            String correlationId, Map<String, Object> before,
            Map<String, Object> after) {
        jdbc.update("""
                INSERT INTO mail_policy_history (
                    tenant_id, policy_version, changed_by, diff_summary,
                    apply_result, correlation_id)
                VALUES (?, ?, ?, ?::jsonb, 'APPLIED', NULLIF(?, ''))
                """, tenantId, policyVersion, userId,
                json.write(Map.of("before", before, "after", after)), value(correlationId));
    }

    int updateConnection(
            Long tenantId, Long userId, UUID connectionId,
            MailDtos.ConnectionUpdateRequest request) {
        return jdbc.update("""
                UPDATE mail_provider_connections
                   SET display_name = ?, mail_domain = NULLIF(?, ''),
                       credential_ref = COALESCE(NULLIF(?, ''), credential_ref),
                       connection_state = ?, last_error_code = NULL,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND connection_id = ? AND version = ?
                """, request.displayName().trim(), value(request.mailDomain()),
                value(request.credentialRef()), request.state().name(), userId,
                tenantId, connectionId, request.version());
    }

    int updateSharedInbox(
            Long tenantId, Long userId, UUID sharedInboxId,
            MailDtos.SharedInboxUpdateRequest request) {
        return jdbc.update("""
                UPDATE mail_shared_inboxes
                   SET display_name = ?, purpose = NULLIF(?, ''),
                       service_target_minutes = ?, lifecycle_state = ?,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND version = ?
                """, request.displayName().trim(), value(request.purpose()),
                request.serviceTargetMinutes(), request.lifecycleState(), userId,
                tenantId, sharedInboxId, request.version());
    }

    void audit(
            Long tenantId, Long userId, String action, String targetType,
            String targetId, String correlationId, Map<String, Object> before,
            Map<String, Object> after) {
        jdbc.update("""
                INSERT INTO mail_audit_events (
                    audit_event_id, tenant_id, actor_user_id, action,
                    target_type, target_id, correlation_id,
                    before_snapshot, after_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, UUID.randomUUID(), tenantId, userId, action,
                targetType, targetId, value(correlationId),
                json.write(before), json.write(after));
    }

    void domainEvent(
            Long tenantId, String aggregateType, UUID aggregateId,
            String eventType, Map<String, Object> payload, String correlationId) {
        jdbc.update("""
                INSERT INTO mail_domain_events (
                    domain_event_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, payload, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), tenantId, aggregateType, aggregateId,
                eventType, json.write(payload), value(correlationId));
    }

    private String value(String input) {
        return input == null ? "" : input.trim();
    }
}
