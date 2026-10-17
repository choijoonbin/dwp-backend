package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_RUN_PREPARE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcPayrollPreparationScopeResolver
        implements PayrollPreparationScopeResolver {

    private final NamedParameterJdbcTemplate jdbc;
    private final PayrollPreparationCanonical canonical;
    private final Clock clock;

    @Autowired
    JdbcPayrollPreparationScopeResolver(
            NamedParameterJdbcTemplate jdbc,
            PayrollPreparationCanonical canonical) {
        this(jdbc, canonical, Clock.systemUTC());
    }

    JdbcPayrollPreparationScopeResolver(
            NamedParameterJdbcTemplate jdbc,
            PayrollPreparationCanonical canonical,
            Clock clock) {
        this.jdbc = jdbc;
        this.canonical = canonical;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollPreparationAuthority.Scope resolve(
            PayrollPreparationAuthority.Subject subject) {
        if (subject == null || !subject.revalidateAt().isAfter(clock.instant())) {
            throw unavailable("Payroll preparation authority is absent or stale.");
        }
        if (!"LEGAL_ENTITY".equals(subject.populationKind())) {
            throw unavailable(
                    "An explicit authoritative Payroll population-kind projection is unavailable.");
        }
        try {
            bindTenant(subject.tenantId());
            List<Row> rows = jdbc.query("""
                    SELECT DISTINCT p.projection_revision, m.legal_entity_id,
                           c.payroll_group_id
                      FROM pay_legal_entity_scope_projections p
                      JOIN pay_legal_entity_scope_members m
                        ON m.tenant_id = p.tenant_id
                       AND m.projection_id = p.projection_id
                      JOIN pay_foundation_configurations c
                        ON c.tenant_id = p.tenant_id
                       AND c.legal_entity_id = m.legal_entity_id
                       AND c.lifecycle_state = 'PUBLISHED'
                     WHERE p.tenant_id = :tenantId
                       AND p.actor_id = :actorId
                       AND p.context_scope_key = :scopeKey
                       AND p.status = 'ACTIVE'
                       AND p.valid_from <= :resolvedAt
                       AND (p.valid_until IS NULL OR p.valid_until > :resolvedAt)
                     ORDER BY m.legal_entity_id, c.payroll_group_id
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", subject.tenantId())
                    .addValue("actorId", subject.actorId())
                    .addValue("scopeKey", subject.contextScopeKey())
                    .addValue("resolvedAt", OffsetDateTime.ofInstant(
                            clock.instant(), ZoneOffset.UTC)),
                    (result, row) -> new Row(
                            result.getString("projection_revision"),
                            result.getObject("legal_entity_id", UUID.class),
                            result.getObject("payroll_group_id", UUID.class)));
            if (rows.isEmpty()) {
                throw unavailable("No current Payroll-owned population scope exists.");
            }
            Set<String> revisions = rows.stream().map(Row::projectionRevision)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (revisions.size() != 1) {
                throw unavailable("Payroll population scope revision is ambiguous.");
            }
            Set<PayrollPreparationAuthority.PopulationTuple> populations = rows.stream()
                    .map(row -> new PayrollPreparationAuthority.PopulationTuple(
                            row.legalEntityId(), row.payrollGroupId(),
                            subject.contextScopeKey()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            String revision = revisions.iterator().next();
            String scopeDigest = canonical.digest(java.util.Map.of(
                    "schema", "PayrollPreparationScope.v1",
                    "tenantId", subject.tenantId(),
                    "actorId", subject.actorId(),
                    "contextScopeKey", subject.contextScopeKey(),
                    "populationKind", subject.populationKind(),
                    "projectionRevision", revision,
                    "populations", populations.stream()
                            .sorted(Comparator
                                    .comparing(PayrollPreparationAuthority.PopulationTuple
                                            ::legalEntityId)
                                    .thenComparing(PayrollPreparationAuthority.PopulationTuple
                                            ::payrollGroupId))
                            .toList()));
            return new PayrollPreparationAuthority.Scope(
                    revision, scopeDigest, populations);
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
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

    private record Row(
            String projectionRevision,
            UUID legalEntityId,
            UUID payrollGroupId) {
    }
}
