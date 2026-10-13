package com.dwp.services.payroll.configuration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Resolves the opaque Gateway scope against Payroll-owned current projections. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.payroll-configuration.wave2.enabled",
        havingValue = "true",
        matchIfMissing = false)
class JdbcPayrollConfigurationScopeResolver
        implements PayrollConfigurationScopeResolver {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    JdbcPayrollConfigurationScopeResolver(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.clock = Clock.systemUTC();
    }

    @Override
    @Transactional(readOnly = true)
    public Scope resolve(PayrollConfigurationRequestContext.VerifiedSubject subject) {
        if (subject == null || !subject.revalidateAt().isAfter(clock.instant())) {
            throw unavailable("Payroll configuration authority is absent or stale.");
        }
        try {
            bindTenant(subject.tenantId());
            List<ScopeRow> rows = jdbc.query("""
                    SELECT p.projection_revision, m.legal_entity_id,
                           c.payroll_group_id
                      FROM pay_legal_entity_scope_projections p
                      JOIN pay_legal_entity_scope_members m
                        ON m.tenant_id = p.tenant_id
                       AND m.projection_id = p.projection_id
                      JOIN pay_foundation_configurations c
                        ON c.tenant_id = p.tenant_id
                       AND c.legal_entity_id = m.legal_entity_id
                       AND c.lifecycle_state <> 'REVERSED'
                     WHERE p.tenant_id = :tenantId
                       AND p.actor_id = :actorId
                       AND p.context_scope_key = :contextScopeKey
                       AND p.policy_revision = :policyRevision
                       AND p.status = 'ACTIVE'
                       AND p.valid_from <= :resolvedAt
                       AND (p.valid_until IS NULL OR p.valid_until > :resolvedAt)
                     ORDER BY m.legal_entity_id, c.payroll_group_id
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", subject.tenantId())
                    .addValue("actorId", subject.actorId())
                    .addValue("contextScopeKey", subject.contextScopeKey())
                    .addValue("policyRevision", subject.policyRevision())
                    .addValue("resolvedAt", OffsetDateTime.ofInstant(
                            clock.instant(), ZoneOffset.UTC)),
                    (result, row) -> new ScopeRow(
                            result.getString("projection_revision"),
                            result.getObject("legal_entity_id", UUID.class),
                            result.getObject("payroll_group_id", UUID.class)));
            if (rows.isEmpty()) {
                throw unavailable("No current Payroll-owned legal-entity/pay-group scope exists.");
            }
            Set<String> revisions = rows.stream()
                    .map(ScopeRow::revision).collect(Collectors.toCollection(LinkedHashSet::new));
            if (revisions.size() != 1) {
                throw unavailable("Payroll population scope revision is ambiguous.");
            }
            Set<UUID> legalEntities = rows.stream().map(ScopeRow::legalEntityId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<UUID> payrollGroups = rows.stream().map(ScopeRow::payrollGroupId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            String revision = revisions.iterator().next();
            String members = legalEntities.stream().sorted().map(UUID::toString)
                    .collect(Collectors.joining(",")) + "|"
                    + payrollGroups.stream().sorted().map(UUID::toString)
                    .collect(Collectors.joining(","));
            String digest = PayrollConfigurationCanonical.textDigest(
                    subject.contextScopeKey() + "|" + revision + "|" + members);
            return new Scope(revision, legalEntities, payrollGroups, digest);
        } catch (BaseException exception) {
            throw exception;
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw unavailable("Payroll population scope cannot be resolved.");
        }
    }

    private void bindTenant(long tenantId) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", Long.toString(tenantId)),
                String.class);
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    private record ScopeRow(
            String revision, UUID legalEntityId, UUID payrollGroupId) {
    }
}
