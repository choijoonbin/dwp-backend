package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.sql.Timestamp;
import java.util.UUID;

/** Product-local replay ledger; callers must consume inside the command transaction. */
@Repository
public class ApprovalStepUpReplayRepository {

    private static final Duration RETENTION = Duration.ofHours(24);
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Autowired
    public ApprovalStepUpReplayRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    ApprovalStepUpReplayRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    public Reservation reserve(
            ApprovalStepUpVerifier.CommandBinding binding,
            String routeContractKey,
            String requestHash) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", binding.tenantId())
                .addValue("actorUserId", binding.actorUserId())
                .addValue("routeContractKey", routeContractKey)
                .addValue("idempotencyKey", binding.idempotencyKey())
                .addValue("requestHash", requestHash);
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO apr_high_risk_idempotency_ledger (
                    idempotency_id, tenant_id, actor_user_id, route_contract_key,
                    idempotency_key, request_hash, status)
                VALUES (:id, :tenantId, :actorUserId, :routeContractKey,
                        :idempotencyKey, :requestHash, 'IN_PROGRESS')
                ON CONFLICT (tenant_id, actor_user_id, route_contract_key, idempotency_key)
                DO NOTHING
                """, params.addValue("id", id));
        if (inserted == 1) {
            return Reservation.reserved(id);
        }
        StoredReservation concurrent = lock(params);
        if (concurrent == null) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Idempotency state could not be resolved.");
        }
        return validate(concurrent, requestHash);
    }

    private StoredReservation lock(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT idempotency_id, request_hash, status, result_receipt::text
                  FROM apr_high_risk_idempotency_ledger
                 WHERE tenant_id = :tenantId AND actor_user_id = :actorUserId
                   AND route_contract_key = :routeContractKey
                   AND idempotency_key = :idempotencyKey
                 FOR UPDATE
                """, params, result -> result.next()
                ? new StoredReservation(
                        result.getObject("idempotency_id", UUID.class),
                        result.getString("request_hash"), result.getString("status"),
                        result.getString("result_receipt"))
                : null);
    }

    private Reservation validate(StoredReservation stored, String requestHash) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new BaseException(
                    ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "The idempotency key is bound to a different command.");
        }
        if ("COMMITTED".equals(stored.status()) && stored.resultReceipt() != null) {
            return Reservation.committed(stored.id(), receipt(stored.resultReceipt()));
        }
        throw new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The idempotent command is still in progress; retry later.");
    }

    public void commit(
            UUID idempotencyId,
            ApprovalStepUpVerifier.VerifiedChallenge challenge) {
        ApprovalStepUpVerifier.CommandBinding binding = challenge.binding();
        CommandReceipt receipt = new CommandReceipt(
                1, binding.targetType(), binding.targetId(), binding.targetVersion(), "COMMITTED");
        int updated = jdbc.update("""
                UPDATE apr_high_risk_idempotency_ledger
                   SET status = 'COMMITTED', challenge_id = :challengeId,
                       result_receipt = CAST(:receipt AS jsonb),
                       committed_at = CURRENT_TIMESTAMP,
                       expires_at = CURRENT_TIMESTAMP + INTERVAL '24 hours'
                 WHERE idempotency_id = :idempotencyId AND status = 'IN_PROGRESS'
                """, new MapSqlParameterSource()
                .addValue("idempotencyId", idempotencyId)
                .addValue("challengeId", challenge.challengeId())
                .addValue("receipt", json(receipt)));
        if (updated != 1) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        }
    }

    public void assertNotConsumed(String challengeId, String nonce) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM apr_step_up_replay_ledger
                     WHERE challenge_id = :challengeId AND nonce = :nonce)
                """, new MapSqlParameterSource()
                .addValue("challengeId", challengeId)
                .addValue("nonce", nonce), Boolean.class);
        if (Boolean.TRUE.equals(exists)) throw replay();
    }

    public void consume(ApprovalStepUpVerifier.VerifiedChallenge challenge) {
        ApprovalStepUpVerifier.CommandBinding binding = challenge.binding();
        try {
            jdbc.update("""
                    INSERT INTO apr_step_up_replay_ledger (
                        replay_id, tenant_id, actor_user_id, challenge_id, nonce,
                        activation_policy, capability_contract_key, scope_ref,
                        target_type, target_id, target_version, command_method,
                        command_path, idempotency_key, payload_sha256,
                        decision_revision, issuer, expires_at)
                    VALUES (
                        :replayId, :tenantId, :actorUserId, :challengeId, :nonce,
                        :activationPolicy, :capabilityContractKey, :scopeRef,
                        :targetType, :targetId, :targetVersion, :commandMethod,
                        :commandPath, :idempotencyKey, :payloadSha256,
                        :decisionRevision, :issuer, :expiresAt)
                    """, new MapSqlParameterSource()
                    .addValue("replayId", UUID.randomUUID())
                    .addValue("tenantId", binding.tenantId())
                    .addValue("actorUserId", binding.actorUserId())
                    .addValue("challengeId", challenge.challengeId())
                    .addValue("nonce", challenge.nonce())
                    .addValue("activationPolicy", binding.activationPolicy())
                    .addValue("capabilityContractKey", binding.capabilityContractKey())
                    .addValue("scopeRef", binding.scopeRef())
                    .addValue("targetType", binding.targetType())
                    .addValue("targetId", binding.targetId())
                    .addValue("targetVersion", binding.targetVersion())
                    .addValue("commandMethod", binding.commandMethod())
                    .addValue("commandPath", binding.commandPath())
                    .addValue("idempotencyKey", binding.idempotencyKey())
                    .addValue("payloadSha256", binding.payloadSha256())
                    .addValue("decisionRevision", binding.decisionRevision())
                    .addValue("issuer", challenge.issuer())
                    .addValue("expiresAt", Timestamp.from(
                            challenge.expiresAt().plus(RETENTION))));
        } catch (DuplicateKeyException exception) {
            throw replay();
        }
    }

    public PurgeResult purgeExpired(int batchSize) {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("Ledger purge batch size must be between 1 and 10000.");
        }
        MapSqlParameterSource params = new MapSqlParameterSource("limit", batchSize);
        int replayRows = jdbc.update("""
                WITH due AS (
                    SELECT replay_id
                      FROM apr_step_up_replay_ledger
                     WHERE expires_at <= CURRENT_TIMESTAMP
                     ORDER BY expires_at, replay_id
                     LIMIT :limit
                     FOR UPDATE SKIP LOCKED)
                DELETE FROM apr_step_up_replay_ledger ledger
                 USING due
                 WHERE ledger.replay_id = due.replay_id
                """, params);
        int idempotencyRows = jdbc.update("""
                WITH due AS (
                    SELECT idempotency_id
                      FROM apr_high_risk_idempotency_ledger
                     WHERE expires_at <= CURRENT_TIMESTAMP
                     ORDER BY expires_at, idempotency_id
                     LIMIT :limit
                     FOR UPDATE SKIP LOCKED)
                DELETE FROM apr_high_risk_idempotency_ledger ledger
                 USING due
                 WHERE ledger.idempotency_id = due.idempotency_id
                """, params);
        return new PurgeResult(replayRows, idempotencyRows);
    }

    private BaseException replay() {
        return new BaseException(
                ErrorCode.STEP_UP_CHALLENGE_REPLAY,
                "The step-up challenge nonce has already been consumed.");
    }

    private CommandReceipt receipt(String value) {
        try {
            CommandReceipt receipt = objectMapper.readValue(value, CommandReceipt.class);
            if (receipt.schemaVersion() != 1
                    || receipt.targetType() == null || receipt.targetType().isBlank()
                    || receipt.targetId() == null || receipt.targetId().isBlank()
                    || receipt.targetVersion() < 0
                    || !"COMMITTED".equals(receipt.status())) {
                throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
            }
            return receipt;
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        }
    }

    public record Reservation(UUID id, boolean committed, CommandReceipt receipt) {
        static Reservation reserved(UUID id) {
            return new Reservation(id, false, null);
        }

        static Reservation committed(UUID id, CommandReceipt receipt) {
            return new Reservation(id, true, receipt);
        }
    }

    public record CommandReceipt(
            int schemaVersion,
            String targetType,
            String targetId,
            long targetVersion,
            String status) {
    }

    public record PurgeResult(int replayRows, int idempotencyRows) {
    }

    private record StoredReservation(
            UUID id, String requestHash, String status, String resultReceipt) {
    }
}
