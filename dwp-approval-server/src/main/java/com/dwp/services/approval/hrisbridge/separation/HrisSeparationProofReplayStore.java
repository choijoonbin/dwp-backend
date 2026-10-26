package com.dwp.services.approval.hrisbridge.separation;

import static com.dwp.services.approval.hrisbridge.separation.HrisSeparationPersistenceModels.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Digest-only, cross-kind replay reservation for APR-002 and HRM-010 proofs. */
public final class HrisSeparationProofReplayStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final HrisSeparationRlsScope scope;
    private final TransactionTemplate requiresNew;

    public HrisSeparationProofReplayStore(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.scope = new HrisSeparationRlsScope(jdbc);
        this.requiresNew = new TransactionTemplate(manager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean reserve(long tenantId, String bindingId, String callerWorkloadId,
            UUID jti, UUID requestNonce, Instant expiresAt, Instant reservedAt) {
        requireEndpoint(bindingId, callerWorkloadId);
        if (jti == null || requestNonce == null || jti.equals(requestNonce)) {
            throw new IllegalArgumentException("HRM-013 proof tokens must be distinct UUIDs.");
        }
        Boolean reserved = requiresNew.execute(status -> {
            scope.bind(tenantId);
            int inserted = jdbc.update("""
                    INSERT INTO apr_hrm013_proof_replays(
                        tenant_id,binding_id,caller_workload_id,token_kind,
                        token_digest,expires_at,reserved_at)
                    VALUES(:tenant,:binding,:caller,'JTI',:jti,:expires,:reserved),
                          (:tenant,:binding,:caller,'REQUEST_NONCE',:nonce,:expires,:reserved)
                    ON CONFLICT (tenant_id,token_digest) DO NOTHING
                    """, new MapSqlParameterSource().addValue("tenant", tenantId)
                    .addValue("binding", bindingId).addValue("caller", callerWorkloadId)
                    .addValue("jti", canonicalUuidDigest(jti))
                    .addValue("nonce", canonicalUuidDigest(requestNonce))
                    .addValue("expires", Timestamp.from(expiresAt))
                    .addValue("reserved", Timestamp.from(reservedAt)));
            if (inserted != 2) status.setRollbackOnly();
            return inserted == 2;
        });
        return Boolean.TRUE.equals(reserved);
    }

    public int purgeExpired(long tenantId, int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("HRM-013 replay cleanup limit is invalid.");
        }
        Integer removed = requiresNew.execute(status -> {
            scope.bind(tenantId);
            return jdbc.update("""
                    DELETE FROM apr_hrm013_proof_replays replay
                     USING (
                        SELECT proof_replay_id
                          FROM apr_hrm013_proof_replays
                         WHERE tenant_id=:tenant
                           AND expires_at + INTERVAL '5 seconds' <= clock_timestamp()
                         ORDER BY expires_at,proof_replay_id
                         LIMIT :limit
                     ) expired
                     WHERE replay.proof_replay_id=expired.proof_replay_id
                    """, new MapSqlParameterSource("tenant", tenantId)
                    .addValue("limit", limit));
        });
        return removed == null ? 0 : removed;
    }

    private static void requireEndpoint(String bindingId, String callerWorkloadId) {
        boolean request = REQUEST_BINDING.equals(bindingId)
                && PEOPLE_WORKLOAD.equals(callerWorkloadId);
        boolean callback = CALLBACK_BINDING.equals(bindingId)
                && APPROVAL_WORKLOAD.equals(callerWorkloadId);
        if (!request && !callback) {
            throw new IllegalArgumentException("Unknown HRM-013 proof endpoint.");
        }
    }
}
