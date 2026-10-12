package com.dwp.services.time.clockingestion;

import com.dwp.services.time.clockingestion.ClockOwnerPolicyProjectionResolver.Member;
import com.dwp.services.time.clockingestion.ClockOwnerPolicyProjectionResolver.PopulationProjection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads integration-owned TIM population projections under the tenant RLS context. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.time.clock-ingestion.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcClockOwnerPolicyProjectionResolver
        implements ClockOwnerPolicyProjectionResolver {

    private static final String SET_TENANT_SQL =
            "SELECT set_config('dwp.tenant_id', ?, true)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcClockOwnerPolicyProjectionResolver(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(10);
    }

    @Override
    public Optional<PopulationProjection> resolve(
            long tenantId, long actorId, String gatewayScopeKey, Instant checkedAt) {
        if (tenantId <= 0 || actorId <= 0 || gatewayScopeKey == null
                || gatewayScopeKey.isBlank() || checkedAt == null) {
            return Optional.empty();
        }
        return inTenant(tenantId, () -> {
            List<PopulationHeader> headers = jdbc.query("""
                    SELECT p.population_public_id, p.projection_revision,
                           g.grant_revision, p.source_digest AS population_digest,
                           g.source_digest AS grant_digest, g.valid_to
                      FROM tim_target_population_actor_grants g
                      JOIN tim_target_population_projections p
                        ON p.tenant_id = g.tenant_id
                       AND p.population_public_id = g.population_public_id
                     WHERE g.tenant_id = ? AND g.actor_id = ?
                       AND g.gateway_scope_key = ?
                       AND g.lifecycle_state = 'ACTIVE'
                       AND p.lifecycle_state = 'ACTIVE'
                       AND g.population_revision = p.projection_revision
                       AND g.valid_from <= ?
                       AND (g.valid_to IS NULL OR g.valid_to > ?)
                       AND p.effective_from <= ?
                       AND (p.effective_to IS NULL OR p.effective_to > ?)
                    """, (row, ignored) -> new PopulationHeader(
                            row.getObject("population_public_id", UUID.class),
                            row.getLong("projection_revision"),
                            row.getLong("grant_revision"),
                            row.getString("population_digest").trim(),
                            row.getString("grant_digest").trim(),
                            row.getTimestamp("valid_to") == null
                                    ? Instant.parse("9999-12-31T23:59:59Z")
                                    : row.getTimestamp("valid_to").toInstant()),
                    tenantId, actorId, gatewayScopeKey,
                    Timestamp.from(checkedAt), Timestamp.from(checkedAt),
                    LocalDate.ofInstant(checkedAt, ZoneOffset.UTC),
                    LocalDate.ofInstant(checkedAt, ZoneOffset.UTC));
            if (headers.size() != 1) return Optional.empty();
            PopulationHeader header = headers.getFirst();
            List<Member> members = jdbc.query("""
                    SELECT worker_public_id, people_assignment_public_id,
                           people_assignment_revision, membership_revision, source_digest
                      FROM tim_target_population_members
                     WHERE tenant_id = ? AND population_public_id = ?
                       AND population_revision = ? AND lifecycle_state = 'ACTIVE'
                       AND effective_from <= ?
                       AND (effective_to IS NULL OR effective_to > ?)
                     ORDER BY worker_public_id, people_assignment_public_id
                     LIMIT 1001
                    """, (row, ignored) -> new Member(
                            row.getObject("worker_public_id", UUID.class),
                            row.getObject("people_assignment_public_id", UUID.class),
                            row.getLong("people_assignment_revision"),
                            row.getLong("membership_revision"),
                            row.getString("source_digest").trim()),
                    tenantId, header.populationPublicId(), header.populationRevision(),
                    LocalDate.ofInstant(checkedAt, ZoneOffset.UTC),
                    LocalDate.ofInstant(checkedAt, ZoneOffset.UTC));
            if (members.isEmpty() || members.size() > 1_000) return Optional.empty();
            return Optional.of(new PopulationProjection(
                    tenantId, actorId, gatewayScopeKey, header.populationPublicId(),
                    header.populationRevision(), header.grantRevision(),
                    header.populationDigest(), header.grantDigest(),
                    header.validUntil(), members));
        });
    }

    private <T> T inTenant(long tenantId, Supplier<T> work) {
        T result = transactions.execute(status -> {
            String expected = Long.toString(tenantId);
            String configured = jdbc.queryForObject(SET_TENANT_SQL, String.class, expected);
            if (!expected.equals(configured)) {
                throw new IllegalStateException("Database tenant context was not established");
            }
            return work.get();
        });
        return Objects.requireNonNull(result, "projection transaction returned null");
    }

    private record PopulationHeader(
            UUID populationPublicId,
            long populationRevision,
            long grantRevision,
            String populationDigest,
            String grantDigest,
            Instant validUntil) {}
}
