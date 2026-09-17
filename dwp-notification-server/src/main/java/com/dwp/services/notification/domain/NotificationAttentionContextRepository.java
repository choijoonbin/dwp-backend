package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionContextReference;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionContextOption;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionScope;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionScopeEvidence;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionTypeTarget;
import com.dwp.services.notification.domain.NotificationAttentionModels.NotificationAttentionContext;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class NotificationAttentionContextRepository {

    static final String CONTEXT_DISCOVERY_SQL = """
            WITH recent_contexts AS (
                SELECT DISTINCT ON (context_key_hash, context_key)
                       kind AS context_kind,
                       context_key,
                       context_key_hash,
                       display_hint,
                       created_at AS last_seen_at
                  FROM ntf_recipient_notification_contexts
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND matchable
                   AND (
                       (:scopeKind = 'RESOURCE'
                        AND kind IN ('PROJECT', 'WORK_ITEM')
                        AND context_key ~
                            '^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,299}$')
                       OR (:scopeKind = 'TOPIC_TOKEN'
                           AND kind = 'TOPIC'
                           AND context_key ~
                               '^[a-z0-9][a-z0-9._-]{0,159}$')
                   )
                 ORDER BY context_key_hash,
                          context_key,
                          created_at DESC,
                          kind,
                          notification_id DESC
            )
            SELECT context_kind,
                   context_key,
                   display_hint,
                   last_seen_at
              FROM recent_contexts
             WHERE :query = ''
                OR LOWER(context_key) = LOWER(:query)
                OR LOWER(COALESCE(display_hint, '')) = LOWER(:query)
                OR STRPOS(LOWER(context_key), LOWER(:query)) > 0
                OR STRPOS(LOWER(COALESCE(display_hint, '')), LOWER(:query)) > 0
             ORDER BY CASE
                          WHEN LOWER(context_key) = LOWER(:query)
                            OR LOWER(COALESCE(display_hint, '')) = LOWER(:query)
                          THEN 0 ELSE 1
                      END,
                      last_seen_at DESC,
                      context_kind,
                      context_key
             LIMIT :limit
            """;

    static final String EVIDENCE_SQL = """
            WITH matching_notifications AS (
                SELECT DISTINCT type.owner_app_key AS app_key,
                       type.type_key,
                       user_notification.notification_id
                  FROM ntf_user_notifications user_notification
                  JOIN ntf_notifications notification
                    ON notification.tenant_id = user_notification.tenant_id
                   AND notification.notification_id = user_notification.notification_id
                  JOIN ntf_notification_type_versions type_version
                    ON type_version.type_version_id = notification.type_version_id
                  JOIN ntf_notification_types type
                    ON type.type_id = type_version.type_id
                 WHERE user_notification.tenant_id = :tenantId
                   AND user_notification.user_id = :userId
                   AND (
                       (:scopeKind = 'ACTOR'
                        AND user_notification.actor_ref = :scopeKey)
                       OR (:scopeKind = 'THREAD'
                           AND notification.thread_key = :scopeKey)
                       OR (:scopeKind = 'RESOURCE'
                           AND :scopeKey IN (
                               user_notification.subject_ref,
                               user_notification.target_ref
                           ))
                       OR EXISTS (
                           SELECT 1
                             FROM ntf_recipient_notification_contexts context
                            WHERE context.tenant_id = user_notification.tenant_id
                              AND context.user_id = user_notification.user_id
                              AND context.notification_id =
                                  user_notification.notification_id
                              AND context.matchable
                              AND context.context_key_hash = :scopeKeyHash
                              AND context.context_key = :scopeKey
                              AND (
                                  (:scopeKind = 'ACTOR'
                                   AND context.kind = 'PERSON')
                                  OR (:scopeKind = 'THREAD'
                                      AND context.kind IN (
                                          'CONVERSATION', 'THREAD', 'CHANNEL'
                                      ))
                                  OR (:scopeKind = 'RESOURCE'
                                      AND context.kind IN ('PROJECT', 'WORK_ITEM'))
                                  OR (:scopeKind = 'TOPIC_TOKEN'
                                      AND context.kind = 'TOPIC')
                              )
                       )
                   )
            )
            SELECT app_key, type_key,
                   COUNT(DISTINCT notification_id) AS affected_notifications
              FROM matching_notifications
             GROUP BY app_key, type_key
             ORDER BY app_key, type_key
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationAttentionContextRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public NotificationAttentionContext notification(
            NotificationRequestContext.Actor actor,
            UUID notificationId) {
        List<ContextRow> rows = jdbc.query("""
                SELECT user_notification.notification_id,
                       type.owner_app_key AS app_key,
                       type.type_key,
                       user_notification.reason_code,
                       user_notification.actor_ref,
                       user_notification.subject_ref,
                       user_notification.target_ref,
                       notification.thread_key
                  FROM ntf_user_notifications user_notification
                  JOIN ntf_notifications notification
                    ON notification.tenant_id = user_notification.tenant_id
                   AND notification.notification_id = user_notification.notification_id
                  JOIN ntf_notification_type_versions type_version
                    ON type_version.type_version_id = notification.type_version_id
                  JOIN ntf_notification_types type
                    ON type.type_id = type_version.type_id
                 WHERE user_notification.tenant_id = :tenantId
                   AND user_notification.user_id = :userId
                   AND user_notification.notification_id = :notificationId
                """, actorParams(actor).addValue("notificationId", notificationId),
                (resultSet, rowNumber) -> new ContextRow(
                        resultSet.getObject("notification_id", UUID.class),
                        resultSet.getString("app_key"),
                        resultSet.getString("type_key"),
                        resultSet.getString("reason_code"),
                        resultSet.getString("actor_ref"),
                        resultSet.getString("subject_ref"),
                        resultSet.getString("target_ref"),
                        resultSet.getString("thread_key")));
        if (rows.isEmpty()) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
        }
        ContextRow row = rows.get(0);
        Map<String, AttentionContextReference> references = new LinkedHashMap<>();
        add(references, "APP_TYPE",
                NotificationAttentionScope.appTypeKey(row.appKey(), row.typeKey()),
                row.appKey() + " / " + row.typeKey());
        add(references, "ACTOR", row.actorRef(), "Actor");
        add(references, "THREAD", row.threadKey(), "Thread");
        add(references, "RESOURCE", row.subjectRef(), "Related resource");
        add(references, "RESOURCE", row.targetRef(), "Related resource");
        structuredContexts(actor, notificationId).forEach(reference ->
                references.putIfAbsent(key(reference.scopeKind(), reference.scopeKey()), reference));
        return new NotificationAttentionContext(
                row.notificationId(), row.appKey(), row.typeKey(), row.reasonCode(),
                List.copyOf(references.values()));
    }

    public List<AttentionContextOption> discover(
            NotificationRequestContext.Actor actor,
            String scopeKind,
            String query,
            int limit) {
        List<RecentContextRow> rows = jdbc.query(
                CONTEXT_DISCOVERY_SQL,
                actorParams(actor)
                        .addValue("scopeKind", scopeKind)
                        .addValue("query", query)
                        .addValue("limit", limit),
                (resultSet, rowNumber) -> new RecentContextRow(
                        resultSet.getString("context_kind"),
                        resultSet.getString("context_key"),
                        resultSet.getString("display_hint"),
                        resultSet.getTimestamp("last_seen_at").toInstant()));
        return rows.stream()
                .filter(row -> isCanonical(scopeKind, row.contextKey()))
                .map(row -> new AttentionContextOption(
                        scopeKind,
                        row.contextKind(),
                        row.contextKey(),
                        displayLabel(row),
                        row.lastSeenAt()))
                .limit(limit)
                .toList();
    }

    public AttentionScopeEvidence evidence(
            NotificationRequestContext.Actor actor,
            AttentionScope scope) {
        if ("APP_TYPE".equals(scope.kind())) {
            return appTypeEvidence(actor, scope.key());
        }
        List<EvidenceRow> rows = jdbc.query(
                EVIDENCE_SQL,
                actorParams(actor)
                        .addValue("scopeKind", scope.kind())
                        .addValue("scopeKey", scope.key())
                        .addValue("scopeKeyHash", scope.hash()),
                (resultSet, rowNumber) -> new EvidenceRow(
                        new AttentionTypeTarget(
                                resultSet.getString("app_key"),
                                resultSet.getString("type_key")),
                        resultSet.getLong("affected_notifications")));
        return evidence(rows);
    }

    private AttentionScopeEvidence appTypeEvidence(
            NotificationRequestContext.Actor actor,
            String scopeKey) {
        int separator = scopeKey.indexOf(':');
        String appKey = scopeKey.substring(0, separator);
        String typeKey = scopeKey.substring(separator + 1);
        List<EvidenceRow> rows = jdbc.query("""
                SELECT type.owner_app_key AS app_key,
                       type.type_key,
                       (
                           SELECT COUNT(*)
                             FROM ntf_user_notifications user_notification
                             JOIN ntf_notifications notification
                               ON notification.tenant_id = user_notification.tenant_id
                              AND notification.notification_id =
                                  user_notification.notification_id
                             JOIN ntf_notification_type_versions recipient_version
                               ON recipient_version.type_version_id =
                                  notification.type_version_id
                            WHERE user_notification.tenant_id = :tenantId
                              AND user_notification.user_id = :userId
                              AND recipient_version.type_id = type.type_id
                       ) AS affected_notifications
                  FROM ntf_notification_types type
                 WHERE type.owner_app_key = :appKey
                   AND type.type_key = :typeKey
                   AND type.lifecycle_state = 'ACTIVE'
                   AND (type.tenant_id IS NULL OR type.tenant_id = :tenantId)
                 ORDER BY (type.tenant_id IS NOT NULL) DESC
                 LIMIT 1
                """, actorParams(actor)
                .addValue("appKey", appKey)
                .addValue("typeKey", typeKey),
                (resultSet, rowNumber) -> new EvidenceRow(
                        new AttentionTypeTarget(
                                resultSet.getString("app_key"),
                                resultSet.getString("type_key")),
                        resultSet.getLong("affected_notifications")));
        return evidence(rows);
    }

    private List<AttentionContextReference> structuredContexts(
            NotificationRequestContext.Actor actor,
            UUID notificationId) {
        return jdbc.query("""
                SELECT kind, context_key, display_hint
                  FROM ntf_recipient_notification_contexts
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND notification_id = :notificationId
                   AND matchable
                 ORDER BY kind, context_key_hash
                """, actorParams(actor).addValue("notificationId", notificationId),
                (resultSet, rowNumber) -> new AttentionContextReference(
                        scopeKind(resultSet.getString("kind")),
                        resultSet.getString("context_key"),
                        resultSet.getString("display_hint")));
    }

    private String scopeKind(String contextKind) {
        return switch (contextKind) {
            case "PERSON" -> "ACTOR";
            case "CONVERSATION", "THREAD", "CHANNEL" -> "THREAD";
            case "PROJECT", "WORK_ITEM" -> "RESOURCE";
            case "TOPIC" -> "TOPIC_TOKEN";
            default -> throw new IllegalArgumentException("Unsupported notification context kind.");
        };
    }

    private boolean isCanonical(String scopeKind, String scopeKey) {
        try {
            NotificationAttentionScope.canonical(scopeKind, scopeKey);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String displayLabel(RecentContextRow row) {
        return row.displayHint() == null || row.displayHint().isBlank()
                ? row.contextKey()
                : row.displayHint();
    }

    private void add(
            Map<String, AttentionContextReference> references,
            String scopeKind,
            String scopeKey,
            String displayHint) {
        if (scopeKey == null) return;
        try {
            NotificationAttentionScope.canonical(scopeKind, scopeKey);
            AttentionContextReference reference = new AttentionContextReference(
                    scopeKind, scopeKey, displayHint);
            references.putIfAbsent(key(scopeKind, scopeKey), reference);
        } catch (IllegalArgumentException ignored) {
            // Legacy free-form references remain readable but are never made matchable.
        }
    }

    private AttentionScopeEvidence evidence(List<EvidenceRow> rows) {
        return new AttentionScopeEvidence(
                rows.stream().map(EvidenceRow::target).toList(),
                rows.stream().mapToLong(EvidenceRow::affectedNotifications).sum());
    }

    private String key(String scopeKind, String scopeKey) {
        return scopeKind + "\u0000" + scopeKey;
    }

    private MapSqlParameterSource actorParams(NotificationRequestContext.Actor actor) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId());
    }

    private record ContextRow(
            UUID notificationId,
            String appKey,
            String typeKey,
            String reasonCode,
            String actorRef,
            String subjectRef,
            String targetRef,
            String threadKey) {
    }

    private record EvidenceRow(
            AttentionTypeTarget target,
            long affectedNotifications) {
    }

    private record RecentContextRow(
            String contextKind,
            String contextKey,
            String displayHint,
            Instant lastSeenAt) {
    }
}
