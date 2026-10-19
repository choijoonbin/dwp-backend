package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.*;

@Component
class Per004WorkloadReplayStore implements ReplayStore {
    private final JdbcTemplate jdbc;
    Per004WorkloadReplayStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public boolean reserve(UUID id, UUID nonce, Instant expiresAt) { return false; }
    @Override @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(Endpoint endpoint, ProofClaims claims) {
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)", String.class, Long.toString(claims.tenantId()));
        return jdbc.update("""
                INSERT INTO hris_reference_configuration_workload_replays
                    (proof_id, request_nonce, tenant_id, binding_id, expires_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, claims.proofId(), claims.requestNonce(), claims.tenantId(), endpoint.bindingId(),
                OffsetDateTime.ofInstant(claims.expiresAt(), ZoneOffset.UTC)) == 1;
    }
}
