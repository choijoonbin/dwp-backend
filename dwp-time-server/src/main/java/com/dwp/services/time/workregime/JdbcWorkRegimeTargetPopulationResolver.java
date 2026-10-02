package com.dwp.services.time.workregime;

import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.PopulationAccess;
import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.TargetMembershipEvidence;
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

/** PostgreSQL-backed, tenant-isolated target-population projection reader. */
@Repository
@ConditionalOnProperty(
        name = "dwp.time.work-regime-api.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcWorkRegimeTargetPopulationResolver
        implements WorkRegimeTargetPopulationResolver {

    private static final String SET_TENANT_SQL =
            "SELECT set_config('dwp.tenant_id', ?, true)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcWorkRegimeTargetPopulationResolver(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(10);
    }

    @Override
    public Optional<PopulationAccess> resolveActorAccess(
            long tenantId, long actorId, String gatewayScopeKey, Instant checkedAt) {
        requireIdentity(tenantId, actorId, gatewayScopeKey, checkedAt);
        return inTenantTransaction(tenantId, () -> {
            List<PopulationAccess> matches = jdbc.query("""
                    SELECT g.tenant_id, g.actor_id, g.gateway_scope_key,
                           p.population_public_id, p.scope_public_ref,
                           p.projection_revision, g.grant_revision,
                           p.source_digest AS population_digest,
                           g.source_digest AS grant_digest,
                           g.valid_to
                      FROM tim_target_population_actor_grants g
                      JOIN tim_target_population_projections p
                        ON p.tenant_id = g.tenant_id
                       AND p.population_public_id = g.population_public_id
                     WHERE g.tenant_id = ?
                       AND g.actor_id = ?
                       AND g.gateway_scope_key = ?
                       AND g.lifecycle_state = 'ACTIVE'
                       AND p.lifecycle_state = 'ACTIVE'
                       AND g.population_revision = p.projection_revision
                       AND g.valid_from <= ?
                       AND (g.valid_to IS NULL OR g.valid_to > ?)
                       AND p.effective_from <= ?
                       AND (p.effective_to IS NULL OR p.effective_to > ?)
                    """, (row, ignored) -> new PopulationAccess(
                            row.getLong("tenant_id"),
                            row.getLong("actor_id"),
                            row.getString("gateway_scope_key"),
                            row.getObject("population_public_id", UUID.class),
                            row.getString("scope_public_ref"),
                            row.getLong("projection_revision"),
                            row.getLong("grant_revision"),
                            row.getString("population_digest").trim(),
                            row.getString("grant_digest").trim(),
                            optionalInstant(row.getTimestamp("valid_to"))),
                    tenantId, actorId, gatewayScopeKey,
                    java.sql.Timestamp.from(checkedAt), java.sql.Timestamp.from(checkedAt),
                    LocalDate.ofInstant(checkedAt, ZoneOffset.UTC),
                    LocalDate.ofInstant(checkedAt, ZoneOffset.UTC));
            return exact(matches);
        });
    }

    @Override
    public Optional<TargetMembershipEvidence> resolveTargetMembership(
            long tenantId,
            UUID populationPublicId,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            EffectivePeriod requiredPeriod,
            Instant checkedAt) {
        requireTenant(tenantId);
        Objects.requireNonNull(populationPublicId, "populationPublicId must not be null");
        Objects.requireNonNull(workerPublicId, "workerPublicId must not be null");
        Objects.requireNonNull(
                peopleAssignmentPublicId, "peopleAssignmentPublicId must not be null");
        if (peopleAssignmentRevision < 0) {
            throw new IllegalArgumentException("peopleAssignmentRevision must not be negative");
        }
        Objects.requireNonNull(requiredPeriod, "requiredPeriod must not be null");
        Objects.requireNonNull(checkedAt, "checkedAt must not be null");
        return inTenantTransaction(tenantId, () -> {
            List<TargetMembershipEvidence> matches = jdbc.query("""
                    SELECT m.tenant_id, m.population_public_id,
                           p.projection_revision, m.worker_public_id,
                           m.people_assignment_public_id, m.people_assignment_revision,
                           m.membership_revision, m.effective_from, m.effective_to,
                           p.source_digest AS population_digest,
                           m.source_digest AS membership_digest
                      FROM tim_target_population_members m
                      JOIN tim_target_population_projections p
                        ON p.tenant_id = m.tenant_id
                       AND p.population_public_id = m.population_public_id
                     WHERE m.tenant_id = ?
                       AND m.population_public_id = ?
                       AND m.worker_public_id = ?
                       AND m.people_assignment_public_id = ?
                       AND m.people_assignment_revision = ?
                       AND m.lifecycle_state = 'ACTIVE'
                       AND p.lifecycle_state = 'ACTIVE'
                       AND m.population_revision = p.projection_revision
                       AND p.effective_from <= ?
                       AND (p.effective_to IS NULL OR ? IS NOT NULL AND p.effective_to >= ?)
                       AND m.effective_from <= ?
                       AND (m.effective_to IS NULL OR ? IS NOT NULL AND m.effective_to >= ?)
                    """, (row, ignored) -> new TargetMembershipEvidence(
                            row.getLong("tenant_id"),
                            row.getObject("population_public_id", UUID.class),
                            row.getLong("projection_revision"),
                            row.getObject("worker_public_id", UUID.class),
                            row.getObject("people_assignment_public_id", UUID.class),
                            row.getLong("people_assignment_revision"),
                            row.getLong("membership_revision"),
                            new EffectivePeriod(
                                    row.getObject("effective_from", LocalDate.class),
                                    row.getObject("effective_to", LocalDate.class)),
                            row.getString("population_digest").trim(),
                            row.getString("membership_digest").trim()),
                    tenantId, populationPublicId, workerPublicId, peopleAssignmentPublicId,
                    peopleAssignmentRevision,
                    requiredPeriod.from(), requiredPeriod.to(), requiredPeriod.to(),
                    requiredPeriod.from(), requiredPeriod.to(), requiredPeriod.to());
            return exact(matches);
        });
    }

    private <T> Optional<T> inTenantTransaction(long tenantId, Supplier<Optional<T>> work) {
        Optional<T> result = transactions.execute(status -> {
            jdbc.queryForObject(SET_TENANT_SQL, String.class, Long.toString(tenantId));
            return work.get();
        });
        return result == null ? Optional.empty() : result;
    }

    private static <T> Optional<T> exact(List<T> matches) {
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static Instant optionalInstant(java.sql.Timestamp value) {
        return value == null ? Instant.parse("9999-12-31T23:59:59Z") : value.toInstant();
    }

    private static void requireIdentity(
            long tenantId, long actorId, String gatewayScopeKey, Instant checkedAt) {
        requireTenant(tenantId);
        if (actorId <= 0) throw new IllegalArgumentException("actorId must be positive");
        if (!HcmEligibilityScopeKey.isCanonical(gatewayScopeKey)) {
            throw new IllegalArgumentException("gatewayScopeKey is not canonical");
        }
        Objects.requireNonNull(checkedAt, "checkedAt must not be null");
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
    }
}
