package com.dwp.services.people.hris.performance.per004;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Both replay identifiers are reserved atomically in the existing inbox; no fourth PER table. */
@Repository
@ConditionalOnProperty(prefix = "dwp.people.hris.per004", name = "enabled", havingValue = "true")
final class JdbcPer004ProofReplayStore implements HrisReferenceConfigurationWorkloadV1.ReplayStore {
    private static final String CONSUMER = "dwp-per004-reference-configuration-proof-v1";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcPer004ProofReplayStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public boolean reserve(UUID proofId, UUID nonce, Instant expiresAt) {
        return false; // A bare reservation lacks the signed tenant and endpoint binding.
    }

    @Override public boolean reserve(HrisReferenceConfigurationWorkloadV1.Endpoint endpoint,
            HrisReferenceConfigurationWorkloadV1.ProofClaims claims) {
        if (endpoint == null || claims == null || claims.tenantId() < 1
                || claims.proofId() == null || claims.requestNonce() == null
                || claims.proofId().equals(claims.requestNonce()) || claims.expiresAt() == null) return false;
        Boolean reserved = transactions.execute(status -> {
            String payload = "{\"expiresAt\":\"" + claims.expiresAt()
                    + "\",\"bindingId\":\"" + endpoint.bindingId() + "\"}";
            String hash = HrisReferenceConfigurationWorkloadV1.sha256(
                    payload.getBytes(StandardCharsets.UTF_8));
            int inserted = jdbc.update("""
                    INSERT INTO sys_domain_event_inbox (
                        consumer_name, event_id, event_source, event_type, schema_version,
                        tenant_id, aggregate_type, aggregate_id, aggregate_sequence,
                        correlation_id, payload, payload_sha256, status, attempt_count,
                        available_at, processed_at, created_at, updated_at)
                    VALUES
                        (?, ?, '/dwp-people-server/hris/performance/per004',
                         'dwp.hris.configuration.workload-proof-reserved.v1', 1, ?,
                         'PER004_WORKLOAD_PROOF', ?, 1, ?, ?::jsonb, ?, 'SUCCEEDED', 0,
                         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        (?, ?, '/dwp-people-server/hris/performance/per004',
                         'dwp.hris.configuration.workload-proof-reserved.v1', 1, ?,
                         'PER004_WORKLOAD_PROOF', ?, 1, ?, ?::jsonb, ?, 'SUCCEEDED', 0,
                         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (consumer_name, event_id) DO NOTHING
                    """, CONSUMER, claims.proofId(), claims.tenantId(), claims.proofId().toString(),
                    claims.proofId().toString(), payload, hash,
                    CONSUMER, claims.requestNonce(), claims.tenantId(), claims.requestNonce().toString(),
                    claims.proofId().toString(), payload, hash);
            if (inserted != 2) status.setRollbackOnly();
            return inserted == 2;
        });
        return Boolean.TRUE.equals(reserved);
    }
}
