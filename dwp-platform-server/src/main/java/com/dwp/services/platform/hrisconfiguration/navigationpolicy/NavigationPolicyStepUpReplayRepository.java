package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;

/** Platform-local one-time challenge ledger participating in the NAV command transaction. */
@Repository
public class NavigationPolicyStepUpReplayRepository {

    private static final Duration RETENTION = Duration.ofHours(24);
    private final NamedParameterJdbcTemplate jdbc;

    public NavigationPolicyStepUpReplayRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void consume(ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge challenge) {
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding = challenge.binding();
        try {
            jdbc.getJdbcTemplate().queryForObject(
                    "SELECT set_config('dwp.tenant_id', ?, true)",
                    String.class, Long.toString(binding.tenantId()));
            int inserted = jdbc.update("""
                    INSERT INTO hris_navigation_policy_step_up_replay (
                        replay_id, tenant_id, actor_user_id, challenge_id, nonce,
                        command_contract_key, activation_policy, capability_contract_key,
                        context_key, scope_ref, target_type, target_id, target_version,
                        command_method, command_path, idempotency_key, payload_sha256,
                        decision_revision, issuer, expires_at)
                    VALUES (
                        :replayId, :tenantId, :actorUserId, :challengeId, :nonce,
                        :commandContractKey, :activationPolicy, :capabilityContractKey,
                        :contextKey, :scopeRef, :targetType, :targetId, :targetVersion,
                        :commandMethod, :commandPath, :idempotencyKey, :payloadSha256,
                        :decisionRevision, :issuer, :expiresAt)
                    """, new MapSqlParameterSource()
                    .addValue("replayId", UUID.randomUUID())
                    .addValue("tenantId", binding.tenantId())
                    .addValue("actorUserId", binding.actorUserId())
                    .addValue("challengeId", challenge.challengeId())
                    .addValue("nonce", challenge.nonce())
                    .addValue("commandContractKey", binding.commandContractKey())
                    .addValue("activationPolicy", binding.activationPolicy())
                    .addValue("capabilityContractKey", binding.capabilityContractKey())
                    .addValue("contextKey", binding.contextKey())
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
            if (inserted != 1) throw unavailable(null);
        } catch (DuplicateKeyException exception) {
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_REPLAY,
                    "The NAV policy step-up challenge was already consumed.");
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private BaseException unavailable(Throwable cause) {
        return cause == null
                ? new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "The NAV policy step-up replay ledger is unavailable.")
                : new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "The NAV policy step-up replay ledger is unavailable.", cause);
    }
}
