package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.domain.NotificationModels.DeliveryProfile;
import com.dwp.services.notification.domain.NotificationModels.DeliveryProfileUpdate;
import com.dwp.services.notification.domain.NotificationModels.Digest;
import com.dwp.services.notification.domain.NotificationModels.QuietHours;
import com.dwp.services.notification.domain.NotificationModels.SubscriptionRule;
import com.dwp.services.notification.domain.NotificationModels.SubscriptionRuleUpdate;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class NotificationPreferenceRepository {

    private static final List<String> CHANNELS =
            List.of("IN_APP", "EMAIL", "WEB_PUSH", "MOBILE_PUSH", "TEAMS", "SLACK");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final NotificationIdempotencyRepository idempotencyRepository;

    public NotificationPreferenceRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            NotificationIdempotencyRepository idempotencyRepository) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotencyRepository = idempotencyRepository;
    }

    public DeliveryProfile profile(NotificationRequestContext.Actor actor) {
        List<DeliveryProfile> rows = jdbc.query("""
                SELECT timezone, quiet_schedule::text AS quiet_schedule,
                       default_channels::text AS default_channels,
                       digest_frequency, digest_local_time, digest_day_of_week,
                       version, updated_at
                  FROM ntf_user_delivery_profiles
                 WHERE tenant_id = :tenantId AND user_id = :userId
                """, actorParams(actor), (resultSet, rowNumber) -> {
            Map<String, Object> quiet = jsonMap(resultSet.getString("quiet_schedule"));
            Object digestDay = resultSet.getObject("digest_day_of_week");
            return new DeliveryProfile(
                    channelMap(jsonList(resultSet.getString("default_channels"))),
                    new QuietHours(
                            booleanValue(quiet.get("enabled"), false),
                            stringValue(quiet.get("start"), "22:00"),
                            stringValue(quiet.get("end"), "07:00"),
                            resultSet.getString("timezone"),
                            intList(quiet.get("days"), List.of(1, 2, 3, 4, 5, 6, 7)),
                            booleanValue(quiet.get("allowUrgentBypass"), true)),
                    new Digest(
                            apiDigestMode(resultSet.getString("digest_frequency")),
                            resultSet.getTime("digest_local_time").toLocalTime().toString(),
                            digestDay == null ? null : ((Number) digestDay).intValue()),
                    NotificationVersionCodec.external(resultSet.getLong("version")),
                    instant(resultSet.getTimestamp("updated_at")));
        });
        return rows.isEmpty() ? defaultProfile() : rows.get(0);
    }

    public DeliveryProfile updateProfile(
            NotificationRequestContext.Actor actor,
            DeliveryProfileUpdate request,
            String idempotencyKey) {
        Request receipt = idempotencyRepository.begin(
                actor, idempotencyKey, "DELIVERY_PROFILE_UPDATE", request);
        DeliveryProfile replay = idempotencyRepository.replay(receipt, DeliveryProfile.class);
        if (replay != null) return replay;
        validateProfile(request);

        MapSqlParameterSource params = actorParams(actor)
                .addValue("timezone", request.quietHours().timeZone().trim())
                .addValue("quietSchedule", json(Map.of(
                        "enabled", request.quietHours().enabled(),
                        "start", request.quietHours().start(),
                        "end", request.quietHours().end(),
                        "days", request.quietHours().days(),
                        "allowUrgentBypass", request.quietHours().allowUrgentBypass())))
                .addValue("defaultChannels", json(enabledChannels(request.channels())))
                .addValue("digestFrequency", databaseDigestMode(request.digest().mode()))
                .addValue("digestLocalTime", Time.valueOf(
                        LocalTime.parse(request.digest().deliveryTime())))
                .addValue("digestDayOfWeek", request.digest().dayOfWeek());
        long expectedVersion = NotificationVersionCodec.nonNegative(
                request.version(), "version");
        params.addValue("expectedVersion", expectedVersion);
        try {
            if (expectedVersion == 0) {
                jdbc.update("""
                        INSERT INTO ntf_user_delivery_profiles (
                            tenant_id, user_id, timezone, quiet_schedule, default_channels,
                            digest_frequency, digest_local_time, digest_day_of_week)
                        VALUES (
                            :tenantId, :userId, :timezone, CAST(:quietSchedule AS jsonb),
                            CAST(:defaultChannels AS jsonb), :digestFrequency,
                            :digestLocalTime, :digestDayOfWeek)
                        """, params);
            } else {
                int updated = jdbc.update("""
                        UPDATE ntf_user_delivery_profiles
                           SET timezone = :timezone,
                               quiet_schedule = CAST(:quietSchedule AS jsonb),
                               default_channels = CAST(:defaultChannels AS jsonb),
                               digest_frequency = :digestFrequency,
                               digest_local_time = :digestLocalTime,
                               digest_day_of_week = :digestDayOfWeek,
                               version = version + 1,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE tenant_id = :tenantId
                           AND user_id = :userId
                           AND version = :expectedVersion
                        """, params);
                requireUpdated(updated);
            }
        } catch (DuplicateKeyException exception) {
            throw stale();
        }
        appendPreferenceOutbox(
                actor,
                "notification.preference.profile.updated",
                "profile:" + actor.userId() + ":" + (expectedVersion + 1));
        DeliveryProfile result = profile(actor);
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    public List<SubscriptionRule> rules(NotificationRequestContext.Actor actor) {
        if (!hasModernRuleSchema()) return List.of();
        return jdbc.query("""
                SELECT rule.rule_id, rule.app_key, rule.type_key, rule.delivery_mode,
                       rule.version, rule.updated_at,
                       COALESCE((
                           SELECT jsonb_object_agg(channel.channel, channel.enabled)
                             FROM ntf_user_subscription_rule_channels channel
                            WHERE channel.tenant_id = rule.tenant_id
                              AND channel.user_id = rule.user_id
                              AND channel.rule_id = rule.rule_id
                       ), '{}'::jsonb)::text AS channels
                  FROM ntf_user_subscription_rules rule
                 WHERE rule.tenant_id = :tenantId AND rule.user_id = :userId
                 ORDER BY rule.app_key, rule.type_key
                """, actorParams(actor), (resultSet, rowNumber) -> new SubscriptionRule(
                resultSet.getString("app_key"),
                resultSet.getString("type_key"),
                resultSet.getString("delivery_mode"),
                jsonBooleanMap(resultSet.getString("channels")),
                resultSet.getObject("rule_id", UUID.class),
                NotificationVersionCodec.external(resultSet.getLong("version")),
                instant(resultSet.getTimestamp("updated_at"))));
    }

    public SubscriptionRule putRule(
            NotificationRequestContext.Actor actor,
            UUID ruleId,
            SubscriptionRuleUpdate request,
            String idempotencyKey) {
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "SUBSCRIPTION_RULE_PUT",
                new RuleMutation(ruleId, request));
        SubscriptionRule replay = idempotencyRepository.replay(
                receipt, SubscriptionRule.class);
        if (replay != null) return replay;

        String appKey = request.appKey().trim();
        String typeKey = request.typeKey().trim();
        requireKnownType(actor, appKey, typeKey);
        validateChannels(request.channels());
        long expectedVersion = request.expectedVersion() == null
                ? 0
                : NotificationVersionCodec.nonNegative(
                        request.expectedVersion(), "expectedVersion");
        MapSqlParameterSource params = actorParams(actor)
                .addValue("ruleId", ruleId)
                .addValue("appKey", appKey)
                .addValue("typeKey", typeKey)
                .addValue("deliveryMode", request.mode())
                .addValue("expectedVersion", expectedVersion);
        try {
            if (expectedVersion == 0) {
                jdbc.update("""
                        INSERT INTO ntf_user_subscription_rules (
                            rule_id, tenant_id, user_id, app_key, type_key, delivery_mode)
                        VALUES (
                            :ruleId, :tenantId, :userId, :appKey, :typeKey, :deliveryMode)
                        """, params);
            } else {
                int updated = jdbc.update("""
                        UPDATE ntf_user_subscription_rules
                           SET app_key = :appKey,
                               type_key = :typeKey,
                               delivery_mode = :deliveryMode,
                               version = version + 1,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE tenant_id = :tenantId
                           AND user_id = :userId
                           AND rule_id = :ruleId
                           AND version = :expectedVersion
                        """, params);
                requireUpdated(updated);
                jdbc.update("""
                        DELETE FROM ntf_user_subscription_rule_channels
                         WHERE tenant_id = :tenantId
                           AND user_id = :userId
                           AND rule_id = :ruleId
                        """, params);
            }
            for (Map.Entry<String, Boolean> channel : request.channels().entrySet()) {
                jdbc.update("""
                        INSERT INTO ntf_user_subscription_rule_channels (
                            rule_id, tenant_id, user_id, channel, enabled)
                        VALUES (:ruleId, :tenantId, :userId, :channel, :enabled)
                        """, new MapSqlParameterSource()
                        .addValue("ruleId", ruleId)
                        .addValue("tenantId", actor.tenantId())
                        .addValue("userId", actor.userId())
                        .addValue("channel", channel.getKey())
                        .addValue("enabled", channel.getValue()));
            }
        } catch (DuplicateKeyException exception) {
            throw stale();
        }
        appendPreferenceOutbox(
                actor,
                "notification.preference.rule.updated",
                "rule:" + ruleId + ":" + (expectedVersion + 1));
        SubscriptionRule result = rule(actor, ruleId);
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    public void deleteRule(
            NotificationRequestContext.Actor actor,
            UUID ruleId,
            long expectedVersion,
            String idempotencyKey) {
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "SUBSCRIPTION_RULE_DELETE",
                Map.of("ruleId", ruleId, "expectedVersion", expectedVersion));
        if (receipt.replayed()) return;
        int updated = jdbc.update("""
                DELETE FROM ntf_user_subscription_rules
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND rule_id = :ruleId
                   AND version = :expectedVersion
                """, actorParams(actor)
                .addValue("ruleId", ruleId)
                .addValue("expectedVersion", expectedVersion));
        requireUpdated(updated);
        appendPreferenceOutbox(
                actor,
                "notification.preference.rule.deleted",
                "rule-delete:" + ruleId + ":" + expectedVersion);
        idempotencyRepository.complete(actor, receipt, Map.of("deleted", true));
    }

    private SubscriptionRule rule(NotificationRequestContext.Actor actor, UUID ruleId) {
        return rules(actor).stream()
                .filter(rule -> rule.ruleId().equals(ruleId))
                .findFirst()
                .orElseThrow(() -> new NotificationException(
                        NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }

    private void requireKnownType(
            NotificationRequestContext.Actor actor,
            String appKey,
            String typeKey) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM ntf_notification_types
                 WHERE owner_app_key = :appKey
                   AND type_key = :typeKey
                   AND lifecycle_state = 'ACTIVE'
                   AND (tenant_id IS NULL OR tenant_id = :tenantId)
                """, actorParams(actor)
                .addValue("appKey", appKey)
                .addValue("typeKey", typeKey), Integer.class);
        if (count == null || count < 1) {
            throw new IllegalArgumentException("The notification type contract is unavailable.");
        }
    }

    private boolean hasModernRuleSchema() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'ntf_user_subscription_rules'
                   AND column_name IN ('app_key', 'type_key')
                """, new MapSqlParameterSource(), Integer.class);
        return count != null && count == 2;
    }

    private void validateProfile(DeliveryProfileUpdate profile) {
        validateChannels(profile.channels());
        if ("WEEKLY".equals(profile.digest().mode())
                && profile.digest().dayOfWeek() == null) {
            throw new IllegalArgumentException("Weekly digest requires a day of week.");
        }
    }

    private void validateChannels(Map<String, Boolean> channels) {
        if (channels == null
                || !channels.keySet().stream().allMatch(CHANNELS::contains)
                || channels.values().stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("Unsupported notification channel.");
        }
    }

    private DeliveryProfile defaultProfile() {
        return new DeliveryProfile(
                channelMap(List.of("IN_APP")),
                new QuietHours(
                        false,
                        "22:00",
                        "07:00",
                        "Asia/Seoul",
                        List.of(1, 2, 3, 4, 5, 6, 7),
                        true),
                new Digest("OFF", "09:00", null),
                "0",
                Instant.EPOCH);
    }

    private Map<String, Boolean> channelMap(List<String> enabled) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        CHANNELS.forEach(channel -> result.put(channel, enabled.contains(channel)));
        return Map.copyOf(result);
    }

    private List<String> enabledChannels(Map<String, Boolean> channels) {
        return CHANNELS.stream()
                .filter(channel -> Boolean.TRUE.equals(channels.get(channel)))
                .toList();
    }

    private String apiDigestMode(String databaseValue) {
        return switch (databaseValue) {
            case "DAILY" -> "DAILY";
            case "WEEKLY" -> "WEEKLY";
            default -> "OFF";
        };
    }

    private String databaseDigestMode(String apiValue) {
        return switch (apiValue) {
            case "DAILY" -> "DAILY";
            case "WEEKLY" -> "WEEKLY";
            default -> "NONE";
        };
    }

    private void appendPreferenceOutbox(
            NotificationRequestContext.Actor actor,
            String eventType,
            String eventKey) {
        jdbc.update("""
                INSERT INTO ntf_outbox_events (
                    outbox_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, event_key, payload, occurred_at)
                VALUES (
                    :outboxId, :tenantId, 'NOTIFICATION_PREFERENCE', :aggregateId,
                    :eventType, :eventKey, CAST(:payload AS jsonb), CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, event_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("tenantId", actor.tenantId())
                .addValue("aggregateId", Long.toString(actor.userId()))
                .addValue("eventType", eventType)
                .addValue("eventKey", eventKey)
                .addValue("payload", json(Map.of("userId", actor.userId()))));
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean booleanValue ? booleanValue : fallback;
    }

    private String stringValue(Object value, String fallback) {
        return value instanceof String stringValue && !stringValue.isBlank()
                ? stringValue : fallback;
    }

    private List<Integer> intList(Object value, List<Integer> fallback) {
        if (!(value instanceof List<?> list)) return fallback;
        List<Integer> values = list.stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue)
                .filter(number -> number >= 1 && number <= 7)
                .distinct()
                .toList();
        return values.isEmpty() ? fallback : values;
    }

    private void requireUpdated(int updated) {
        if (updated != 1) throw stale();
    }

    private NotificationException stale() {
        return new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
    }

    private MapSqlParameterSource actorParams(NotificationRequestContext.Actor actor) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize notification preference.", exception);
        }
    }

    private Map<String, Object> jsonMap(String value) {
        try {
            return objectMapper.readValue(value == null ? "{}" : value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid notification preference JSON.", exception);
        }
    }

    private List<String> jsonList(String value) {
        try {
            return objectMapper.readValue(value == null ? "[]" : value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid notification channel preference.", exception);
        }
    }

    private Map<String, Boolean> jsonBooleanMap(String value) {
        try {
            return objectMapper.readValue(value == null ? "{}" : value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid notification rule channels.", exception);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record RuleMutation(UUID ruleId, SubscriptionRuleUpdate request) {
    }
}
