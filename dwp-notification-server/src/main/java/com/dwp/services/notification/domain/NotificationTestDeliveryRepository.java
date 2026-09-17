package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.domain.NotificationTestDeliveryModels.TestDelivery;
import com.dwp.services.notification.domain.NotificationTestDeliveryModels.TestDeliveryRate;
import com.dwp.services.notification.domain.NotificationTestDeliveryModels.TestDeliveryStage;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class NotificationTestDeliveryRepository {

    static final String INSERT_SQL = """
            INSERT INTO ntf_test_delivery_receipts (
                test_id, tenant_id, user_id, requested_channels, state,
                stage_results, version, created_at, expires_at)
            VALUES (
                :testId, :tenantId, :userId, CAST(:requestedChannels AS jsonb), :state,
                CAST(:stageResults AS jsonb), 1, :createdAt, :expiresAt)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final NotificationIdempotencyRepository idempotencyRepository;

    public NotificationTestDeliveryRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            NotificationIdempotencyRepository idempotencyRepository) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotencyRepository = idempotencyRepository;
    }

    public TestDelivery create(
            NotificationRequestContext.Actor actor,
            TestDelivery delivery,
            int minuteLimit,
            int dailyLimit,
            String idempotencyKey) {
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "TEST_DELIVERY_CREATE",
                delivery.requestedChannels());
        TestDelivery replay = idempotencyRepository.replay(receipt, TestDelivery.class);
        if (replay != null) return replay;

        lockRateWindow(actor);
        TestDeliveryRate rate = rate(actor, delivery.createdAt());
        if (rate.lastMinute() >= minuteLimit || rate.lastDay() >= dailyLimit) {
            throw new NotificationException(NotificationErrorCode.TEST_DELIVERY_RATE_LIMITED);
        }
        jdbc.update(INSERT_SQL, actorParams(actor)
                .addValue("testId", delivery.testId())
                .addValue("requestedChannels", json(delivery.requestedChannels()))
                .addValue("state", delivery.state())
                .addValue("stageResults", json(delivery.stages()))
                .addValue("createdAt", delivery.createdAt())
                .addValue("expiresAt", delivery.expiresAt()));
        appendAudit(actor, delivery.testId());
        idempotencyRepository.complete(actor, receipt, delivery);
        return delivery;
    }

    public TestDelivery get(
            NotificationRequestContext.Actor actor,
            UUID testId,
            Instant now) {
        List<TestDelivery> rows = jdbc.query("""
                SELECT test_id, requested_channels::text AS requested_channels,
                       state, stage_results::text AS stage_results,
                       created_at, expires_at
                  FROM ntf_test_delivery_receipts
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND test_id = :testId
                   AND expires_at > :now
                """, actorParams(actor)
                .addValue("testId", testId)
                .addValue("now", now),
                (resultSet, rowNumber) -> new TestDelivery(
                        resultSet.getObject("test_id", UUID.class),
                        resultSet.getString("state"),
                        strings(resultSet.getString("requested_channels")),
                        stages(resultSet.getString("stage_results")),
                        instant(resultSet.getTimestamp("created_at")),
                        instant(resultSet.getTimestamp("expires_at")),
                        null));
        return rows.stream().findFirst().orElseThrow(() -> new NotificationException(
                NotificationErrorCode.TEST_DELIVERY_NOT_FOUND));
    }

    public Set<String> activePushEndpoints(NotificationRequestContext.Actor actor) {
        return Set.copyOf(jdbc.query("""
                SELECT DISTINCT channel
                  FROM ntf_user_delivery_endpoints
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND state = 'ACTIVE'
                   AND channel IN ('WEB_PUSH', 'MOBILE_PUSH')
                """, actorParams(actor),
                (resultSet, rowNumber) -> resultSet.getString("channel")));
    }

    private void lockRateWindow(NotificationRequestContext.Actor actor) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                new MapSqlParameterSource(
                        "lockKey", "test-delivery:" + actor.tenantId() + ":" + actor.userId()),
                resultSet -> null);
    }

    private TestDeliveryRate rate(
            NotificationRequestContext.Actor actor,
            Instant now) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (
                           WHERE created_at > :minuteStart
                       ) AS last_minute,
                       COUNT(*) FILTER (
                           WHERE created_at > :dayStart
                       ) AS last_day
                  FROM ntf_test_delivery_receipts
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                """, actorParams(actor)
                .addValue("minuteStart", now.minus(1, ChronoUnit.MINUTES))
                .addValue("dayStart", now.minus(1, ChronoUnit.DAYS)),
                (resultSet, rowNumber) -> new TestDeliveryRate(
                        resultSet.getLong("last_minute"),
                        resultSet.getLong("last_day")));
    }

    private void appendAudit(
            NotificationRequestContext.Actor actor,
            UUID testId) {
        jdbc.update("""
                INSERT INTO ntf_attention_rule_audit_outbox (
                    event_id, tenant_id, user_id, subject_type, subject_id,
                    event_type, subject_version, occurred_at)
                VALUES (
                    :eventId, :tenantId, :userId, 'TEST_DELIVERY', :testId,
                    'TEST_DELIVERY_CREATED', 1, CURRENT_TIMESTAMP)
                """, actorParams(actor)
                .addValue("eventId", UUID.randomUUID())
                .addValue("testId", testId));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize test delivery receipt.", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid test delivery channel receipt.", exception);
        }
    }

    private List<TestDeliveryStage> stages(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid test delivery stage receipt.", exception);
        }
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
