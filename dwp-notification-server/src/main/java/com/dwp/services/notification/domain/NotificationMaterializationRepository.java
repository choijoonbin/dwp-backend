package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationModels.ChangeSignal;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationModels.MaterializationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class NotificationMaterializationRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final NotificationDeliveryAdmissionService admissionService;
    private final NotificationRuntimeAdmissionRepository runtimeAdmissionRepository;

    public NotificationMaterializationRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            NotificationDeliveryAdmissionService admissionService,
            NotificationRuntimeAdmissionRepository runtimeAdmissionRepository) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.admissionService = admissionService;
        this.runtimeAdmissionRepository = runtimeAdmissionRepository;
    }

    public TemplateContract contract(
            long tenantId,
            String typeKey,
            String sourceEventType,
            int sourceSchemaVersion,
            String locale) {
        List<TemplateContract> contracts = jdbc.query("""
                SELECT type_version.type_version_id,
                       COALESCE(type_version.tenant_id, 0) AS type_scope_tenant_id,
                       template.template_version_id,
                       COALESCE(template.tenant_id, 0) AS template_scope_tenant_id,
                       tenant_template.template_revision_id AS template_override_revision_id,
                       type.type_key,
                       type.owner_app_key,
                       type_version.priority,
                       type_version.urgency,
                       template.locale,
                       COALESCE(tenant_template.title_template, template.title_template)
                           AS title_template,
                       COALESCE(tenant_template.preview_template, template.preview_template)
                           AS preview_template,
                       COALESCE(tenant_template.body_template, template.body_template)
                           AS body_template,
                       CASE
                           WHEN tenant_template.template_revision_id IS NULL
                               THEN template.action_payload
                           ELSE jsonb_set(
                               template.action_payload,
                               '{label}',
                               to_jsonb(tenant_template.action_label),
                               TRUE)
                       END::text AS action_payload
                  FROM ntf_notification_types type
                  JOIN ntf_notification_type_versions type_version
                    ON type_version.type_id = type.type_id
                  JOIN ntf_template_versions template
                    ON template.type_version_id = type_version.type_version_id
                  LEFT JOIN LATERAL (
                      SELECT tenant_revision.template_revision_id,
                             tenant_revision.title_template,
                             tenant_revision.preview_template,
                             tenant_revision.body_template,
                             tenant_revision.action_label,
                             tenant_revision.revision
                        FROM ntf_tenant_template_revisions tenant_revision
                       WHERE tenant_revision.tenant_id = :tenantId
                         AND tenant_revision.type_version_id = type_version.type_version_id
                         AND tenant_revision.channel = template.channel
                         AND tenant_revision.locale = template.locale
                         AND tenant_revision.state = 'PUBLISHED'
                       ORDER BY tenant_revision.revision DESC
                       LIMIT 1
                  ) tenant_template ON TRUE
                 WHERE type.type_key = :typeKey
                   AND type.lifecycle_state = 'ACTIVE'
                   AND type_version.lifecycle_state = 'ACTIVE'
                   AND type_version.source_event_type = :sourceEventType
                   AND :sourceSchemaVersion BETWEEN type_version.min_schema_version
                                                AND type_version.max_schema_version
                   AND template.channel = 'IN_APP'
                   AND template.state = 'PUBLISHED'
                   AND template.locale IN (:locale, 'ko-KR', 'en-US')
                   AND (type.tenant_id IS NULL OR type.tenant_id = :tenantId)
                 ORDER BY (type.tenant_id IS NOT NULL) DESC,
                          (template.locale = :locale) DESC,
                          (tenant_template.template_revision_id IS NOT NULL) DESC,
                          type_version.version DESC,
                          tenant_template.revision DESC NULLS LAST,
                          template.version DESC
                 LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("typeKey", typeKey)
                .addValue("sourceEventType", sourceEventType)
                .addValue("sourceSchemaVersion", sourceSchemaVersion)
                .addValue("locale", locale),
                (resultSet, rowNumber) -> new TemplateContract(
                        resultSet.getObject("type_version_id", UUID.class),
                        resultSet.getLong("type_scope_tenant_id"),
                        resultSet.getObject("template_version_id", UUID.class),
                        resultSet.getLong("template_scope_tenant_id"),
                        resultSet.getObject("template_override_revision_id", UUID.class),
                        resultSet.getString("type_key"),
                        resultSet.getString("owner_app_key"),
                        resultSet.getString("priority"),
                        resultSet.getString("urgency"),
                        resultSet.getString("locale"),
                        resultSet.getString("title_template"),
                        resultSet.getString("preview_template"),
                        resultSet.getString("body_template"),
                        jsonMap(resultSet.getString("action_payload"))));
        if (contracts.isEmpty()) {
            throw new NotificationException(
                    NotificationErrorCode.NOTIFICATION_CONTRACT_QUARANTINED);
        }
        return contracts.get(0);
    }

    public PersistenceResult materialize(
            long tenantId,
            DirectMaterializationRequest request,
            TemplateContract contract,
            RenderedContent content,
            String sourcePayloadHash,
            String correlationId,
            Set<Long> entitledRecipientUserIds) {
        IntentResolution intent = createIntent(
                tenantId, request, contract, sourcePayloadHash, correlationId);
        if (intent.duplicate()) {
            if (!intent.sourcePayloadHash().equals(sourcePayloadHash)) {
                throw new NotificationException(
                        NotificationErrorCode.NOTIFICATION_CONTRACT_QUARANTINED,
                        "The source event ID was reused with a different payload.");
            }
            if (intent.notificationId() == null && "SUPPRESSED".equals(intent.decision())) {
                return new PersistenceResult(
                        new MaterializationResult(
                                intent.intentId(), null, 0, true, "0"),
                        List.of());
            }
            if (intent.notificationId() == null) {
                throw new IllegalStateException("Duplicate intent has no notification projection.");
            }
            int recipients = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM ntf_user_notifications
                     WHERE tenant_id = :tenantId
                       AND notification_id = :notificationId
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("notificationId", intent.notificationId()), Integer.class);
            return new PersistenceResult(
                    new MaterializationResult(
                            intent.intentId(), intent.notificationId(), recipients, true, "0"),
                    List.of());
        }

        Set<Long> requestedRecipients = Set.copyOf(request.recipientUserIds());
        if (!requestedRecipients.containsAll(entitledRecipientUserIds)) {
            throw new IllegalArgumentException(
                    "Entitled notification recipients must be a subset of requested recipients.");
        }
        if (entitledRecipientUserIds.isEmpty()) {
            jdbc.update("""
                    UPDATE ntf_notification_intents
                       SET decision = 'SUPPRESSED',
                           reason_code = 'RECIPIENT_APP_ENTITLEMENT_DENIED'
                     WHERE tenant_id = :tenantId AND intent_id = :intentId
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("intentId", intent.intentId()));
            return new PersistenceResult(
                    new MaterializationResult(
                            intent.intentId(), null, 0, false, "0"),
                    List.of());
        }

        Instant occurredAt = request.occurredAt() == null ? Instant.now() : request.occurredAt();
        String threadKey = request.threadKey() == null || request.threadKey().isBlank()
                ? request.sourceEventId().toString()
                : request.threadKey().trim();
        UUID notificationId = upsertNotification(
                tenantId, request, contract, content, threadKey, occurredAt);
        if (notificationId == null) {
            notificationId = activeThreadId(tenantId, contract.typeKey(), threadKey);
            jdbc.update("""
                    UPDATE ntf_notification_intents
                       SET notification_id = :notificationId,
                           decision = 'DUPLICATE'
                     WHERE tenant_id = :tenantId AND intent_id = :intentId
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("intentId", intent.intentId())
                    .addValue("notificationId", notificationId));
            return new PersistenceResult(
                    new MaterializationResult(
                            intent.intentId(), notificationId, 0, true, "0"),
                    List.of());
        }
        jdbc.update("""
                UPDATE ntf_notification_intents
                   SET notification_id = :notificationId
                 WHERE tenant_id = :tenantId AND intent_id = :intentId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("intentId", intent.intentId())
                .addValue("notificationId", notificationId));

        List<ChangeSignal> signals = new ArrayList<>();
        long highestChangeVersion = 0;
        for (Long recipientUserId : new LinkedHashSet<>(entitledRecipientUserIds)) {
            if (!runtimeAdmissionRepository.inAppDeliveryEnabled(
                    tenantId,
                    recipientUserId,
                    contract.ownerAppKey(),
                    contract.typeKey())) {
                continue;
            }
            if (!admissionService.admittedRecipient(
                    tenantId, recipientUserId, request, contract, Instant.now())) continue;
            long changeVersion = materializeRecipient(
                    tenantId,
                    recipientUserId,
                    notificationId,
                    request,
                    contract,
                    content,
                    occurredAt);
            highestChangeVersion = Math.max(highestChangeVersion, changeVersion);
            signals.add(new ChangeSignal(
                    tenantId, recipientUserId, changeVersion, notificationId));
        }
        appendOutbox(
                tenantId,
                notificationId,
                request.sourceEventId(),
                intent.intentId(),
                signals,
                occurredAt);
        return new PersistenceResult(
                new MaterializationResult(
                        intent.intentId(),
                        notificationId,
                        signals.size(),
                        false,
                        NotificationVersionCodec.external(highestChangeVersion)),
                List.copyOf(signals));
    }

    private IntentResolution createIntent(
            long tenantId,
            DirectMaterializationRequest request,
            TemplateContract contract,
            String sourcePayloadHash,
            String correlationId) {
        UUID intentId = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("intentId", intentId)
                .addValue("tenantId", tenantId)
                .addValue("sourceEventId", request.sourceEventId())
                .addValue("sourceEventType", request.sourceEventType())
                .addValue("sourceSchemaVersion", request.sourceSchemaVersion())
                .addValue("typeKey", contract.typeKey())
                .addValue("typeVersionId", contract.typeVersionId())
                .addValue("typeScopeTenantId", contract.typeScopeTenantId())
                .addValue("sourcePayloadHash", sourcePayloadHash)
                .addValue("correlationId", correlationId)
                .addValue("reasonCode", reason(request.reasonCode()))
                .addValue("variables", json(request.variables()))
                .addValue("occurredAt", Timestamp.from(
                        request.occurredAt() == null ? Instant.now() : request.occurredAt()));
        List<UUID> inserted = jdbc.query("""
                    INSERT INTO ntf_notification_intents (
                        intent_id, tenant_id, source_event_id, source_event_type,
                        source_schema_version, type_key, type_version_id, type_scope_tenant_id,
                        source_payload_hash,
                        correlation_id, decision, reason_code, sanitized_variables,
                        occurred_at)
                    VALUES (
                        :intentId, :tenantId, :sourceEventId, :sourceEventType,
                        :sourceSchemaVersion, :typeKey, :typeVersionId, :typeScopeTenantId,
                        :sourcePayloadHash,
                        :correlationId, 'MATERIALIZED', :reasonCode,
                        CAST(:variables AS jsonb), :occurredAt)
                    ON CONFLICT (tenant_id, source_event_id, type_key) DO NOTHING
                    RETURNING intent_id
                    """, parameters,
                (resultSet, rowNumber) -> resultSet.getObject("intent_id", UUID.class));
        if (!inserted.isEmpty()) {
            return new IntentResolution(
                    inserted.get(0), null, sourcePayloadHash, "MATERIALIZED", false);
        }
        return jdbc.queryForObject("""
                SELECT intent_id, notification_id, source_payload_hash, decision
                  FROM ntf_notification_intents
                 WHERE tenant_id = :tenantId
                   AND source_event_id = :sourceEventId
                   AND type_key = :typeKey
                """, parameters,
                (resultSet, rowNumber) -> new IntentResolution(
                        resultSet.getObject("intent_id", UUID.class),
                        resultSet.getObject("notification_id", UUID.class),
                        resultSet.getString("source_payload_hash"),
                        resultSet.getString("decision"),
                        true));
    }

    private UUID upsertNotification(
            long tenantId,
            DirectMaterializationRequest request,
            TemplateContract contract,
            RenderedContent content,
            String threadKey,
            Instant occurredAt) {
        List<UUID> notificationIds = jdbc.query("""
                INSERT INTO ntf_notifications (
                    notification_id, tenant_id, type_version_id, type_scope_tenant_id,
                    type_key, thread_key,
                    actor_ref, subject_ref, target_ref, safe_body, action_payload,
                    sanitized_template_variables, first_activity_at, last_activity_at)
                VALUES (
                    :notificationId, :tenantId, :typeVersionId, :typeScopeTenantId,
                    :typeKey, :threadKey,
                    :actorRef, :subjectRef, :targetRef, :safeBody,
                    CAST(:actionPayload AS jsonb), CAST(:variables AS jsonb),
                    :occurredAt, :occurredAt)
                ON CONFLICT (tenant_id, type_key, thread_key)
                    WHERE closed_at IS NULL
                DO UPDATE SET
                    type_version_id = EXCLUDED.type_version_id,
                    type_scope_tenant_id = EXCLUDED.type_scope_tenant_id,
                    actor_ref = EXCLUDED.actor_ref,
                    subject_ref = EXCLUDED.subject_ref,
                    target_ref = EXCLUDED.target_ref,
                    safe_body = EXCLUDED.safe_body,
                    action_payload = EXCLUDED.action_payload,
                    sanitized_template_variables = EXCLUDED.sanitized_template_variables,
                    last_activity_at = GREATEST(
                        ntf_notifications.last_activity_at, EXCLUDED.last_activity_at),
                    occurrence_count = ntf_notifications.occurrence_count + 1,
                    version = ntf_notifications.version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE EXCLUDED.last_activity_at >= ntf_notifications.last_activity_at
                RETURNING notification_id
                """, new MapSqlParameterSource()
                .addValue("notificationId", UUID.randomUUID())
                .addValue("tenantId", tenantId)
                .addValue("typeVersionId", contract.typeVersionId())
                .addValue("typeScopeTenantId", contract.typeScopeTenantId())
                .addValue("typeKey", contract.typeKey())
                .addValue("threadKey", threadKey)
                .addValue("actorRef", request.actorReference())
                .addValue("subjectRef", request.subjectReference())
                .addValue("targetRef", request.targetReference())
                .addValue("safeBody", content.body())
                .addValue("actionPayload", json(content.action()))
                .addValue("variables", json(request.variables()))
                .addValue("occurredAt", Timestamp.from(occurredAt)),
                (resultSet, rowNumber) -> resultSet.getObject("notification_id", UUID.class));
        return notificationIds.isEmpty() ? null : notificationIds.get(0);
    }

    private UUID activeThreadId(long tenantId, String typeKey, String threadKey) {
        return jdbc.queryForObject("""
                SELECT notification_id
                  FROM ntf_notifications
                 WHERE tenant_id = :tenantId
                   AND type_key = :typeKey
                   AND thread_key = :threadKey
                   AND closed_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("typeKey", typeKey)
                .addValue("threadKey", threadKey), UUID.class);
    }

    private long materializeRecipient(
            long tenantId,
            long userId,
            UUID notificationId,
            DirectMaterializationRequest request,
            TemplateContract contract,
            RenderedContent content,
            Instant occurredAt) {
        MapSqlParameterSource identity = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userId", userId)
                .addValue("notificationId", notificationId);
        jdbc.update("""
                INSERT INTO ntf_user_counters (tenant_id, user_id)
                VALUES (:tenantId, :userId)
                ON CONFLICT (tenant_id, user_id) DO NOTHING
                """, identity);
        Counter counter = jdbc.queryForObject("""
                SELECT unread_count, actionable_unread_count, urgent_count, counter_version
                  FROM ntf_user_counters
                 WHERE tenant_id = :tenantId AND user_id = :userId
                 FOR UPDATE
                """, identity, (resultSet, rowNumber) -> new Counter(
                resultSet.getLong("unread_count"),
                resultSet.getLong("actionable_unread_count"),
                resultSet.getLong("urgent_count"),
                resultSet.getLong("counter_version")));
        List<ExistingProjection> existingRows = jdbc.query("""
                SELECT inbox_state, read_at, snoozed_until, action_required, effective_priority
                  FROM ntf_user_notifications
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND notification_id = :notificationId
                 FOR UPDATE
                """, identity, (resultSet, rowNumber) -> new ExistingProjection(
                resultSet.getString("inbox_state"),
                resultSet.getTimestamp("read_at") == null
                        ? null : resultSet.getTimestamp("read_at").toInstant(),
                resultSet.getTimestamp("snoozed_until") == null
                        ? null : resultSet.getTimestamp("snoozed_until").toInstant(),
                resultSet.getBoolean("action_required"),
                resultSet.getString("effective_priority")));
        ExistingProjection existing = existingRows.isEmpty() ? null : existingRows.get(0);
        long changeVersion = counter.version() + 1;
        jdbc.update("""
                INSERT INTO ntf_user_notifications (
                    tenant_id, user_id, notification_id, reason_code,
                    effective_priority, action_required, due_at, locale,
                    in_app_template_version_id, template_scope_tenant_id,
                    template_override_revision_id,
                    actor_ref, subject_ref, target_ref,
                    safe_title, safe_preview, safe_body, action_payload,
                    search_text, inbox_state, first_activity_at,
                    last_activity_at, occurrence_count, change_version,
                    target_state, target_state_reason)
                VALUES (
                    :tenantId, :userId, :notificationId, :reasonCode,
                    :priority, :actionRequired, :dueAt, :locale,
                    :templateVersionId, :templateScopeTenantId, :templateOverrideRevisionId,
                    :actorRef, :subjectRef, :targetRef,
                    :safeTitle, :safePreview, :safeBody, CAST(:actionPayload AS jsonb),
                    :searchText, 'ACTIVE', :occurredAt,
                    :occurredAt, 1, :changeVersion, 'AVAILABLE', NULL)
                ON CONFLICT (tenant_id, user_id, notification_id)
                DO UPDATE SET
                    reason_code = EXCLUDED.reason_code,
                    effective_priority = EXCLUDED.effective_priority,
                    action_required = EXCLUDED.action_required,
                    due_at = EXCLUDED.due_at,
                    locale = EXCLUDED.locale,
                    in_app_template_version_id = EXCLUDED.in_app_template_version_id,
                    template_override_revision_id = EXCLUDED.template_override_revision_id,
                    actor_ref = EXCLUDED.actor_ref,
                    subject_ref = EXCLUDED.subject_ref,
                    target_ref = EXCLUDED.target_ref,
                    safe_title = EXCLUDED.safe_title,
                    safe_preview = EXCLUDED.safe_preview,
                    safe_body = EXCLUDED.safe_body,
                    action_payload = EXCLUDED.action_payload,
                    search_text = EXCLUDED.search_text,
                    target_state = 'AVAILABLE',
                    target_state_reason = NULL,
                    inbox_state = 'ACTIVE',
                    read_at = NULL,
                    completed_at = NULL,
                    snoozed_until = NULL,
                    last_activity_at = GREATEST(
                        ntf_user_notifications.last_activity_at, EXCLUDED.last_activity_at),
                    occurrence_count = ntf_user_notifications.occurrence_count + 1,
                    change_version = EXCLUDED.change_version,
                    version = ntf_user_notifications.version + 1,
                    updated_at = CURRENT_TIMESTAMP
                """, identity
                .addValue("reasonCode", reason(request.reasonCode()))
                .addValue("priority", contract.priority())
                .addValue("actionRequired", request.actionRequired())
                .addValue("dueAt", request.dueAt() == null ? null : Timestamp.from(request.dueAt()))
                .addValue("locale", contract.locale())
                .addValue("templateVersionId", contract.templateVersionId())
                .addValue("templateScopeTenantId", contract.templateScopeTenantId())
                .addValue("templateOverrideRevisionId", contract.templateOverrideRevisionId())
                .addValue("actorRef", request.actorReference())
                .addValue("subjectRef", request.subjectReference())
                .addValue("targetRef", request.targetReference())
                .addValue("safeTitle", content.title())
                .addValue("safePreview", content.preview())
                .addValue("safeBody", content.body())
                .addValue("actionPayload", json(content.action()))
                .addValue("searchText", content.title() + " " + content.preview())
                .addValue("occurredAt", Timestamp.from(occurredAt))
                .addValue("changeVersion", changeVersion));

        int unreadDelta = existing == null || !visibleUnread(existing, occurredAt) ? 1 : 0;
        int actionableDelta = request.actionRequired()
                && (existing == null || !visibleActionable(existing, occurredAt)) ? 1 : 0;
        int urgentDelta = "URGENT".equals(contract.priority())
                && (existing == null || !visibleUrgent(existing, occurredAt)) ? 1 : 0;
        jdbc.update("""
                UPDATE ntf_user_counters
                   SET unread_count = GREATEST(0, unread_count + :unreadDelta),
                       actionable_unread_count =
                           GREATEST(0, actionable_unread_count + :actionableDelta),
                       urgent_count = GREATEST(0, urgent_count + :urgentDelta),
                       counter_version = :changeVersion,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND user_id = :userId
                """, identity
                .addValue("unreadDelta", unreadDelta)
                .addValue("actionableDelta", actionableDelta)
                .addValue("urgentDelta", urgentDelta));
        return changeVersion;
    }

    private void appendOutbox(
            long tenantId,
            UUID notificationId,
            UUID sourceEventId,
            UUID intentId,
            List<ChangeSignal> signals,
            Instant occurredAt) {
        jdbc.update("""
                INSERT INTO ntf_outbox_events (
                    outbox_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, event_key, payload, occurred_at)
                VALUES (
                    :outboxId, :tenantId, 'NOTIFICATION', :aggregateId,
                    'notification.materialized', :eventKey,
                    CAST(:payload AS jsonb), :occurredAt)
                ON CONFLICT (tenant_id, event_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("tenantId", tenantId)
                .addValue("aggregateId", notificationId.toString())
                .addValue("eventKey", NotificationOutboxEventKeys.materialized(
                        sourceEventId, intentId))
                .addValue("payload", json(Map.of(
                        "notificationId", notificationId.toString(),
                        "recipientCount", signals.size(),
                        "changedUsers", signals.stream()
                                .map(ChangeSignal::userId)
                                .toList())))
                .addValue("occurredAt", Timestamp.from(occurredAt)));
    }

    private boolean visibleUnread(ExistingProjection existing, Instant now) {
        return "ACTIVE".equals(existing.inboxState())
                && existing.readAt() == null
                && (existing.snoozedUntil() == null || !existing.snoozedUntil().isAfter(now));
    }

    private boolean visibleActionable(ExistingProjection existing, Instant now) {
        return visibleUnread(existing, now) && existing.actionRequired();
    }

    private boolean visibleUrgent(ExistingProjection existing, Instant now) {
        return visibleUnread(existing, now) && "URGENT".equals(existing.priority());
    }

    private String reason(String value) {
        return value == null || value.isBlank() ? "DIRECT_RECIPIENT" : value.trim();
    }

    private Map<String, Object> jsonMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid notification action template.", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize notification materialization.", exception);
        }
    }

    public record RenderedContent(
            String title,
            String preview,
            String body,
            Map<String, Object> action) {
    }

    public record PersistenceResult(
            MaterializationResult result,
            List<ChangeSignal> signals) {
    }

    private record IntentResolution(
            UUID intentId,
            UUID notificationId,
            String sourcePayloadHash,
            String decision,
            boolean duplicate) {
    }

    private record Counter(long unread, long actionable, long urgent, long version) {
    }

    private record ExistingProjection(
            String inboxState,
            Instant readAt,
            Instant snoozedUntil,
            boolean actionRequired,
            String priority) {
    }
}
