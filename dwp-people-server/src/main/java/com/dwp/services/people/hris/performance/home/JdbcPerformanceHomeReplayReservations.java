package com.dwp.services.people.hris.performance.home;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Atomically reserves both proof identifiers in the existing durable inbox. */
@Repository
@ConditionalOnProperty(prefix = "dwp.people.hris.performance-home", name = "enabled",
        havingValue = "true")
final class JdbcPerformanceHomeReplayReservations
        implements PerformanceHomeSecurityPorts.ReplayReservations {

    private static final String CONSUMER =
            "dwp-performance-home-materialization-proof-v1";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcPerformanceHomeReplayReservations(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean reserve(
            UUID proofId, UUID requestNonce, long tenantId, Instant expiresAt) {
        if (proofId == null || requestNonce == null || proofId.equals(requestNonce)
                || tenantId < 1 || expiresAt == null) return false;
        Boolean reserved = transactions.execute(status -> {
            String payload = "{\"expiresAt\":\"" + expiresAt + "\"}";
            int inserted = jdbc.update("""
                    INSERT INTO sys_domain_event_inbox(
                        consumer_name,event_id,event_source,event_type,schema_version,
                        tenant_id,aggregate_type,aggregate_id,aggregate_sequence,
                        correlation_id,payload,payload_sha256,status,attempt_count,
                        available_at,processed_at,created_at,updated_at)
                    VALUES
                        (:consumer,:jti,'/dwp-people-server/hris/performance/home',
                         'dwp.hris.home.workload-proof-reserved.v1',1,:tenant,
                         'HOME_WORKLOAD_PROOF',:jtiText,1,:jtiText,
                         CAST(:payload AS jsonb),:hash,'SUCCEEDED',0,
                         CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                        (:consumer,:nonce,'/dwp-people-server/hris/performance/home',
                         'dwp.hris.home.workload-proof-reserved.v1',1,:tenant,
                         'HOME_WORKLOAD_PROOF',:nonceText,1,:jtiText,
                         CAST(:payload AS jsonb),:hash,'SUCCEEDED',0,
                         CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    ON CONFLICT (consumer_name,event_id) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("consumer", CONSUMER)
                    .addValue("jti", proofId)
                    .addValue("nonce", requestNonce)
                    .addValue("tenant", tenantId)
                    .addValue("jtiText", proofId.toString())
                    .addValue("nonceText", requestNonce.toString())
                    .addValue("payload", payload)
                    .addValue("hash", sha256(payload)));
            if (inserted != 2) status.setRollbackOnly();
            return inserted == 2;
        });
        return Boolean.TRUE.equals(reserved);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
