package com.dwp.services.notification.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationDeliveryEndpointModels.DeliveryEndpoint;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class NotificationDeliveryEndpointRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationIdempotencyRepository idempotencyRepository;
    private final AuditOutboxRecorder audit;

    public NotificationDeliveryEndpointRepository(
            NamedParameterJdbcTemplate jdbc,
            NotificationIdempotencyRepository idempotencyRepository,
            AuditOutboxRecorder audit) {
        this.jdbc = jdbc;
        this.idempotencyRepository = idempotencyRepository;
        this.audit = audit;
    }

    public List<DeliveryEndpoint> list(NotificationRequestContext.Actor actor) {
        return jdbc.query("""
                SELECT endpoint_id, channel, display_name, platform, endpoint_hint,
                       state, last_seen_at, created_at, revoked_at, version
                  FROM ntf_user_delivery_endpoints
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                 ORDER BY CASE state WHEN 'ACTIVE' THEN 0 ELSE 1 END,
                          last_seen_at DESC, endpoint_id
                """, actorParams(actor), (resultSet, rowNumber) -> new DeliveryEndpoint(
                resultSet.getObject("endpoint_id", UUID.class),
                resultSet.getString("channel"),
                resultSet.getString("display_name"),
                resultSet.getString("platform"),
                resultSet.getString("endpoint_hint"),
                resultSet.getString("state"),
                instant(resultSet.getTimestamp("last_seen_at")),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("revoked_at")),
                NotificationVersionCodec.external(resultSet.getLong("version"))));
    }

    public DeliveryEndpoint revoke(
            NotificationRequestContext.Actor actor,
            UUID endpointId,
            long expectedVersion,
            String idempotencyKey) {
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "DELIVERY_ENDPOINT_REVOKE",
                Map.of("endpointId", endpointId, "expectedVersion", expectedVersion));
        DeliveryEndpoint replay = idempotencyRepository.replay(receipt, DeliveryEndpoint.class);
        if (replay != null) return replay;

        DeliveryEndpoint current = endpoint(actor, endpointId);
        if ("REVOKED".equals(current.state())) {
            idempotencyRepository.complete(actor, receipt, current);
            return current;
        }
        if (!NotificationVersionCodec.external(expectedVersion).equals(current.version())) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
        int updated = jdbc.update("""
                UPDATE ntf_user_delivery_endpoints
                   SET state = 'REVOKED',
                       revoked_at = CURRENT_TIMESTAMP,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND endpoint_id = :endpointId
                   AND state = 'ACTIVE'
                   AND version = :expectedVersion
                """, actorParams(actor)
                .addValue("endpointId", endpointId)
                .addValue("expectedVersion", expectedVersion));
        if (updated != 1) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
        appendOutbox(actor, endpointId, expectedVersion + 1);
        DeliveryEndpoint result = endpoint(actor, endpointId);
        recordAudit(actor, result);
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    private DeliveryEndpoint endpoint(
            NotificationRequestContext.Actor actor,
            UUID endpointId) {
        return list(actor).stream()
                .filter(endpoint -> endpoint.endpointId().equals(endpointId))
                .findFirst()
                .orElseThrow(() -> new NotificationException(
                        NotificationErrorCode.DELIVERY_ENDPOINT_NOT_FOUND));
    }

    private void appendOutbox(
            NotificationRequestContext.Actor actor,
            UUID endpointId,
            long version) {
        jdbc.update("""
                INSERT INTO ntf_outbox_events (
                    outbox_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, event_key, payload, occurred_at)
                VALUES (
                    :outboxId, :tenantId, 'NOTIFICATION_DELIVERY_ENDPOINT', :aggregateId,
                    'notification.delivery-endpoint.revoked', :eventKey,
                    jsonb_build_object(
                        'userId', :userId,
                        'endpointId', CAST(:aggregateId AS text),
                        'state', 'REVOKED'),
                    CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, event_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId())
                .addValue("aggregateId", endpointId.toString())
                .addValue("eventKey", "delivery-endpoint:" + endpointId + ":" + version));
    }

    private void recordAudit(
            NotificationRequestContext.Actor actor,
            DeliveryEndpoint endpoint) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action("notification.delivery-endpoint.revoked")
                .outcome("SUCCESS")
                .severity("INFO")
                .riskScore(20)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-notification-server")
                .sourceModule("notification-preferences")
                .targetType("NOTIFICATION_DELIVERY_ENDPOINT")
                .targetId(endpoint.endpointId().toString())
                .afterState(Map.of(
                        "channel", endpoint.channel(),
                        "platform", endpoint.platform(),
                        "state", endpoint.state()))
                .retentionClass("STANDARD")
                .build());
    }

    private MapSqlParameterSource actorParams(NotificationRequestContext.Actor actor) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId());
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
