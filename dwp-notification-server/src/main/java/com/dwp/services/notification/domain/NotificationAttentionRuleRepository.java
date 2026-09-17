package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRule;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlMutationRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleCreateRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleDeletion;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleUpdateRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionScope;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationAttentionRuleRepository {

    static final String RULE_SELECT = """
            SELECT rule.rule_id, rule.scope_kind, rule.scope_key, rule.display_label,
                   rule.effect, rule.enabled,
                   rule.starts_at, rule.expires_at, rule.source, rule.managed,
                   rule.exception_allowed, rule.version, rule.created_at, rule.updated_at,
                   COALESCE(
                       jsonb_object_agg(channel.channel, channel.enabled)
                           FILTER (WHERE channel.channel IS NOT NULL),
                       '{}'::jsonb
                   )::text AS channels
              FROM ntf_user_attention_rules rule
              LEFT JOIN ntf_user_attention_rule_channels channel
                ON channel.tenant_id = rule.tenant_id
               AND channel.user_id = rule.user_id
               AND channel.rule_id = rule.rule_id
            """;
    static final String OWNER_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final NotificationIdempotencyRepository idempotencyRepository;

    public NotificationAttentionRuleRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            NotificationIdempotencyRepository idempotencyRepository) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotencyRepository = idempotencyRepository;
    }

    public List<AttentionRule> list(NotificationRequestContext.Actor actor) {
        return queryRules(RULE_SELECT + """
                 WHERE rule.tenant_id = :tenantId
                   AND rule.user_id = :userId
                 GROUP BY rule.rule_id
                 ORDER BY rule.managed DESC, rule.updated_at DESC, rule.rule_id
                """, actorParams(actor));
    }

    public Optional<AttentionRule> find(
            NotificationRequestContext.Actor actor,
            UUID ruleId) {
        List<AttentionRule> rows = queryRules(RULE_SELECT + """
                 WHERE rule.tenant_id = :tenantId
                   AND rule.user_id = :userId
                   AND rule.rule_id = :ruleId
                 GROUP BY rule.rule_id
                """, actorParams(actor).addValue("ruleId", ruleId));
        return rows.stream().findFirst();
    }

    public Optional<AttentionRule> findByScope(
            NotificationRequestContext.Actor actor,
            AttentionScope scope) {
        List<AttentionRule> rows = queryRules(RULE_SELECT + """
                 WHERE rule.tenant_id = :tenantId
                   AND rule.user_id = :userId
                   AND rule.scope_kind = :scopeKind
                   AND rule.scope_key_hash = :scopeKeyHash
                 GROUP BY rule.rule_id
                """, actorParams(actor)
                .addValue("scopeKind", scope.kind())
                .addValue("scopeKeyHash", scope.hash()));
        return rows.stream().findFirst();
    }

    public int activeCount(NotificationRequestContext.Actor actor) {
        return activeCount(actor, "", Map.of());
    }

    public int activeVipCount(NotificationRequestContext.Actor actor) {
        return activeCount(
                actor,
                "AND scope_kind = :scopeKind AND effect = :effect",
                Map.of("scopeKind", "ACTOR", "effect", "PRIORITIZE"));
    }

    public int activeFollowCount(NotificationRequestContext.Actor actor) {
        return activeCount(actor, "AND effect = :effect", Map.of("effect", "FOLLOW"));
    }

    private int activeCount(
            NotificationRequestContext.Actor actor,
            String predicate,
            Map<String, String> predicateValues) {
        MapSqlParameterSource params = actorParams(actor);
        predicateValues.forEach(params::addValue);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM ntf_user_attention_rules
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND enabled
                   AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                """ + predicate, params, Integer.class);
        return count == null ? 0 : count;
    }

    public AttentionRule create(
            NotificationRequestContext.Actor actor,
            AttentionScope scope,
            AttentionRuleCreateRequest request,
            Settings governance,
            String idempotencyKey) {
        Request receipt = idempotencyRepository.begin(
                actor, idempotencyKey, "ATTENTION_RULE_CREATE", request);
        AttentionRule replay = idempotencyRepository.replay(receipt, AttentionRule.class);
        if (replay != null) return replay;
        lockOwner(actor);
        enforceCapacity(
                actor, null, scope.kind(), request.effect(), request.enabled(), governance);

        UUID ruleId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO ntf_user_attention_rules (
                        rule_id, tenant_id, user_id, scope_kind, scope_key,
                        scope_key_hash, display_label, effect, enabled, starts_at, expires_at,
                        source, managed, exception_allowed)
                    VALUES (
                        :ruleId, :tenantId, :userId, :scopeKind, :scopeKey,
                        :scopeKeyHash, :displayLabel, :effect, :enabled, :startsAt, :expiresAt,
                        'USER', FALSE, FALSE)
                    """, actorParams(actor)
                    .addValue("ruleId", ruleId)
                    .addValue("scopeKind", scope.kind())
                    .addValue("scopeKey", scope.key())
                    .addValue("scopeKeyHash", scope.hash())
                    .addValue("displayLabel", request.displayLabel())
                    .addValue("effect", request.effect())
                    .addValue("enabled", enabled(request.enabled()))
                    .addValue("startsAt", request.startsAt())
                    .addValue("expiresAt", request.expiresAt()));
        } catch (DuplicateKeyException exception) {
            throw stale();
        }
        replaceChannels(actor, ruleId, request.channels());
        appendAudit(actor, ruleId, "RULE_CREATED", scope.kind(), request.effect(), 1L);
        AttentionRule result = required(actor, ruleId);
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    public AttentionRule update(
            NotificationRequestContext.Actor actor,
            UUID ruleId,
            AttentionRuleUpdateRequest request,
            long expectedVersion,
            Settings governance,
            String idempotencyKey) {
        Request receipt = idempotencyRepository.begin(
                actor, idempotencyKey, "ATTENTION_RULE_UPDATE", request);
        AttentionRule replay = idempotencyRepository.replay(receipt, AttentionRule.class);
        if (replay != null) return replay;
        lockOwner(actor);

        AttentionRule current = required(actor, ruleId);
        requireEditable(current);
        requireVersion(current, expectedVersion);
        enforceCapacity(
                actor,
                current,
                current.scopeKind(),
                request.effect(),
                request.enabled(),
                governance);
        int updated = jdbc.update("""
                UPDATE ntf_user_attention_rules
                   SET display_label = :displayLabel,
                       effect = :effect,
                       enabled = :enabled,
                       starts_at = :startsAt,
                       expires_at = :expiresAt,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND rule_id = :ruleId
                   AND version = :expectedVersion
                """, actorParams(actor)
                .addValue("ruleId", ruleId)
                .addValue("displayLabel", request.displayLabel())
                .addValue("effect", request.effect())
                .addValue("enabled", enabled(request.enabled()))
                .addValue("startsAt", request.startsAt())
                .addValue("expiresAt", request.expiresAt())
                .addValue("expectedVersion", expectedVersion));
        if (updated != 1) throw stale();
        replaceChannels(actor, ruleId, request.channels());
        appendAudit(
                actor, ruleId, "RULE_UPDATED", current.scopeKind(),
                request.effect(), expectedVersion + 1);
        AttentionRule result = required(actor, ruleId);
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    public void delete(
            NotificationRequestContext.Actor actor,
            UUID ruleId,
            long expectedVersion,
            String idempotencyKey) {
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "ATTENTION_RULE_DELETE",
                Map.of("ruleId", ruleId, "expectedVersion", expectedVersion));
        AttentionRuleDeletion replay = idempotencyRepository.replay(
                receipt, AttentionRuleDeletion.class);
        if (replay != null) return;
        lockOwner(actor);

        AttentionRule current = required(actor, ruleId);
        requireEditable(current);
        requireVersion(current, expectedVersion);
        int deleted = jdbc.update("""
                DELETE FROM ntf_user_attention_rules
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND rule_id = :ruleId
                   AND version = :expectedVersion
                """, actorParams(actor)
                .addValue("ruleId", ruleId)
                .addValue("expectedVersion", expectedVersion));
        if (deleted != 1) throw stale();
        appendAudit(
                actor, ruleId, "RULE_DELETED", current.scopeKind(),
                current.effect(), expectedVersion);
        idempotencyRepository.complete(
                actor, receipt, new AttentionRuleDeletion(ruleId, expectedVersion));
    }

    public AttentionRule applyControl(
            NotificationRequestContext.Actor actor,
            UUID notificationId,
            AttentionScope scope,
            String displayLabel,
            AttentionControlMutationRequest request,
            long expectedVersion,
            Settings governance,
            boolean previewCurrent,
            String idempotencyKey) {
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "ATTENTION_CONTROL_APPLY",
                Map.of("notificationId", notificationId, "request", request));
        AttentionRule replay = idempotencyRepository.replay(receipt, AttentionRule.class);
        if (replay != null) return replay;
        if (!previewCurrent) {
            throw new NotificationException(NotificationErrorCode.ATTENTION_PREVIEW_STALE);
        }
        lockOwner(actor);

        AttentionRule current = findByScope(actor, scope).orElse(null);
        UUID ruleId;
        long nextVersion;
        String eventType;
        if (current == null) {
            if (expectedVersion != 0) throw stale();
            enforceCapacity(actor, null, scope.kind(), request.effect(), true, governance);
            ruleId = UUID.randomUUID();
            try {
                jdbc.update("""
                        INSERT INTO ntf_user_attention_rules (
                            rule_id, tenant_id, user_id, scope_kind, scope_key,
                            scope_key_hash, display_label, effect, enabled,
                            starts_at, expires_at, source, managed, exception_allowed)
                        VALUES (
                            :ruleId, :tenantId, :userId, :scopeKind, :scopeKey,
                            :scopeKeyHash, :displayLabel, :effect, TRUE,
                            NULL, :expiresAt, 'USER', FALSE, FALSE)
                        """, actorParams(actor)
                        .addValue("ruleId", ruleId)
                        .addValue("scopeKind", scope.kind())
                        .addValue("scopeKey", scope.key())
                        .addValue("scopeKeyHash", scope.hash())
                        .addValue("displayLabel", displayLabel)
                        .addValue("effect", request.effect())
                        .addValue("expiresAt", request.expiresAt()));
            } catch (DuplicateKeyException exception) {
                throw stale();
            }
            nextVersion = 1L;
            eventType = "RULE_CREATED";
        } else {
            if (expectedVersion == 0) throw stale();
            requireEditable(current);
            requireVersion(current, expectedVersion);
            enforceCapacity(actor, current, scope.kind(), request.effect(), true, governance);
            int updated = jdbc.update("""
                    UPDATE ntf_user_attention_rules
                       SET display_label = :displayLabel,
                           effect = :effect,
                           enabled = TRUE,
                           starts_at = NULL,
                           expires_at = :expiresAt,
                           version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenantId
                       AND user_id = :userId
                       AND rule_id = :ruleId
                       AND version = :expectedVersion
                    """, actorParams(actor)
                    .addValue("ruleId", current.ruleId())
                    .addValue("displayLabel", displayLabel)
                    .addValue("effect", request.effect())
                    .addValue("expiresAt", request.expiresAt())
                    .addValue("expectedVersion", expectedVersion));
            if (updated != 1) throw stale();
            ruleId = current.ruleId();
            nextVersion = expectedVersion + 1;
            eventType = "RULE_UPDATED";
        }
        replaceChannels(actor, ruleId, Map.of());
        appendAudit(
                actor, ruleId, eventType, scope.kind(), request.effect(), nextVersion);
        AttentionRule result = required(actor, ruleId);
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    private AttentionRule required(
            NotificationRequestContext.Actor actor,
            UUID ruleId) {
        return find(actor, ruleId).orElseThrow(() -> new NotificationException(
                NotificationErrorCode.ATTENTION_RULE_NOT_FOUND));
    }

    private void lockOwner(NotificationRequestContext.Actor actor) {
        jdbc.query(
                OWNER_LOCK_SQL,
                new MapSqlParameterSource(
                        "lockKey", "attention-rules:" + actor.tenantId() + ":" + actor.userId()),
                resultSet -> null);
    }

    private List<AttentionRule> queryRules(
            String sql,
            MapSqlParameterSource params) {
        return jdbc.query(sql, params, (resultSet, rowNumber) -> new AttentionRule(
                resultSet.getObject("rule_id", UUID.class),
                resultSet.getString("scope_kind"),
                resultSet.getString("scope_key"),
                resultSet.getString("display_label"),
                resultSet.getString("effect"),
                channelMap(resultSet.getString("channels")),
                instant(resultSet.getTimestamp("starts_at")),
                instant(resultSet.getTimestamp("expires_at")),
                resultSet.getString("source"),
                resultSet.getBoolean("managed"),
                resultSet.getBoolean("exception_allowed"),
                resultSet.getBoolean("enabled"),
                NotificationVersionCodec.external(resultSet.getLong("version")),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at"))));
    }

    private Map<String, Boolean> channelMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid attention rule channel state.", exception);
        }
    }

    private void replaceChannels(
            NotificationRequestContext.Actor actor,
            UUID ruleId,
            Map<String, Boolean> channels) {
        jdbc.update("""
                DELETE FROM ntf_user_attention_rule_channels
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND rule_id = :ruleId
                """, actorParams(actor).addValue("ruleId", ruleId));
        SqlParameterSource[] batch = channels.entrySet().stream()
                .map(entry -> actorParams(actor)
                        .addValue("ruleId", ruleId)
                        .addValue("channel", entry.getKey())
                        .addValue("enabled", entry.getValue()))
                .toArray(SqlParameterSource[]::new);
        if (batch.length > 0) {
            jdbc.batchUpdate("""
                    INSERT INTO ntf_user_attention_rule_channels (
                        tenant_id, user_id, rule_id, channel, enabled)
                    VALUES (:tenantId, :userId, :ruleId, :channel, :enabled)
                    """, batch);
        }
    }

    private void appendAudit(
            NotificationRequestContext.Actor actor,
            UUID subjectId,
            String eventType,
            String scopeKind,
            String effect,
            long version) {
        jdbc.update("""
                INSERT INTO ntf_attention_rule_audit_outbox (
                    event_id, tenant_id, user_id, subject_type, subject_id,
                    event_type, scope_kind, effect, subject_version, occurred_at)
                VALUES (
                    :eventId, :tenantId, :userId, 'ATTENTION_RULE', :subjectId,
                    :eventType, :scopeKind, :effect, :version, CURRENT_TIMESTAMP)
                """, actorParams(actor)
                .addValue("eventId", UUID.randomUUID())
                .addValue("subjectId", subjectId)
                .addValue("eventType", eventType)
                .addValue("scopeKind", scopeKind)
                .addValue("effect", effect)
                .addValue("version", version));
    }

    private void requireEditable(AttentionRule rule) {
        if (rule.managed() || !"USER".equals(rule.source())) {
            throw new NotificationException(NotificationErrorCode.ATTENTION_POLICY_LOCKED);
        }
    }

    private void requireVersion(AttentionRule rule, long expectedVersion) {
        if (!NotificationVersionCodec.external(expectedVersion).equals(rule.version())) {
            throw stale();
        }
    }

    private NotificationException stale() {
        return new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
    }

    private boolean enabled(Boolean value) {
        return value == null || value;
    }

    private boolean consumesCapacity(AttentionRule rule) {
        return rule != null
                && rule.enabled()
                && (rule.expiresAt() == null || rule.expiresAt().isAfter(Instant.now()));
    }

    private void enforceCapacity(
            NotificationRequestContext.Actor actor,
            AttentionRule current,
            String scopeKind,
            String effect,
            Boolean requestedEnabled,
            Settings governance) {
        if (!enabled(requestedEnabled)) return;
        if (!consumesCapacity(current)
                && activeCount(actor) >= governance.maxActiveUserRules()) {
            throw limit("active attention rule");
        }
        if (vip(scopeKind, effect)
                && !vip(current)
                && activeVipCount(actor) >= governance.maxVipRules()) {
            throw limit("VIP attention rule");
        }
        if (follow(effect)
                && !follow(current)
                && activeFollowCount(actor) >= governance.maxFollowRules()) {
            throw limit("follow attention rule");
        }
    }

    private boolean vip(AttentionRule rule) {
        return consumesCapacity(rule) && vip(rule.scopeKind(), rule.effect());
    }

    private boolean vip(String scopeKind, String effect) {
        return "ACTOR".equals(scopeKind) && "PRIORITIZE".equals(effect);
    }

    private boolean follow(AttentionRule rule) {
        return consumesCapacity(rule) && follow(rule.effect());
    }

    private boolean follow(String effect) {
        return "FOLLOW".equals(effect);
    }

    private NotificationException limit(String kind) {
        return new NotificationException(
                NotificationErrorCode.ATTENTION_RULE_LIMIT_REACHED,
                "The tenant " + kind + " limit was reached.");
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
