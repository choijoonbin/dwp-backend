package com.dwp.services.people.hris.separation.hrm013;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.ReplayReservation;

/** Digest-only atomic reservation for HRM-008 through HRM-010 proof identifiers. */
public final class JdbcHrm013ProofReplayStore
        implements Hrm013PersistencePorts.ProofReplayStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    public JdbcHrm013ProofReplayStore(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this(jdbc, manager, Clock.systemUTC());
    }

    JdbcHrm013ProofReplayStore(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            Clock clock) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(manager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Override
    public boolean reserve(ReplayReservation reservation) {
        Instant reservedAt = clock.instant();
        if (!reservation.expiresAt().isAfter(reservedAt)
                || reservation.expiresAt().isAfter(reservedAt.plusSeconds(35))) {
            return false;
        }
        String jtiDigest = Hrm013PersistenceModels.tokenDigest(reservation.jti());
        String nonceDigest = Hrm013PersistenceModels.tokenDigest(reservation.requestNonce());
        if (jtiDigest.equals(nonceDigest)) return false;

        Boolean result = requiresNew.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, reservation.tenantId());
            int inserted = jdbc.update("""
                    INSERT INTO ppl_hrm013_callback_proof_replays(
                        tenant_id,binding_id,caller_workload_id,token_kind,
                        token_digest,expires_at,reserved_at)
                    VALUES(:tenant,:binding,:caller,'JTI',:jti,:expires,:reserved),
                          (:tenant,:binding,:caller,'REQUEST_NONCE',:nonce,:expires,:reserved)
                    ON CONFLICT (tenant_id,token_digest) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("tenant", reservation.tenantId())
                    .addValue("binding", reservation.bindingId())
                    .addValue("caller", reservation.callerWorkloadId())
                    .addValue("jti", jtiDigest)
                    .addValue("nonce", nonceDigest)
                    .addValue("expires", Timestamp.from(reservation.expiresAt()))
                    .addValue("reserved", Timestamp.from(reservedAt)));
            if (inserted != 2) status.setRollbackOnly();
            return inserted == 2;
        });
        return Boolean.TRUE.equals(result);
    }
}
