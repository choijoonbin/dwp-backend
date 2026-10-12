package com.dwp.services.approval.hrisbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reserves jti and requestNonce atomically in V58's single namespace. */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hris-bridge", name = "enabled", havingValue = "true")
final class HrisApprovalReplayStore implements HrisWorkloadProofV2.ReplayStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    HrisApprovalReplayStore(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(manager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean reserve(UUID proofId, UUID requestNonce, Instant expiresAt) {
        return false; // Production callers must provide the contextual overload.
    }

    @Override
    public boolean reserve(HrisApprovalBridgeV2.Endpoint endpoint,
            HrisWorkloadProofV2.ProofClaims claims) {
        Boolean result = requiresNew.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO apr_hris_bridge_proof_replays(
                        token_id,token_kind,tenant_id,binding_id,caller_workload_id,expires_at)
                    VALUES(:jti,'JTI',:tenant,:binding,:caller,:expiry),
                          (:nonce,'REQUEST_NONCE',:tenant,:binding,:caller,:expiry)
                    ON CONFLICT (token_id) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("jti", claims.proofId())
                    .addValue("nonce", claims.requestNonce())
                    .addValue("tenant", claims.tenantId())
                    .addValue("binding", endpoint.bindingId())
                    .addValue("caller", endpoint.callerWorkloadId())
                    .addValue("expiry", java.sql.Timestamp.from(claims.expiresAt())));
            if (inserted != 2) status.setRollbackOnly();
            return inserted == 2;
        });
        return Boolean.TRUE.equals(result);
    }
}
