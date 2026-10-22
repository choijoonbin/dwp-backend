package com.dwp.services.payroll.statutory.pay004;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAY_RETIREMENT_WORKBENCH.enabled",
        havingValue = "true", matchIfMissing = false)
class JdbcPay004ScopeResolver implements Pay004ScopeResolver {

    private final NamedParameterJdbcTemplate jdbc;
    private final Pay004Canonical canonical;
    private final Clock clock;

    JdbcPay004ScopeResolver(
            NamedParameterJdbcTemplate jdbc, Pay004Canonical canonical) {
        this(jdbc, canonical, Clock.systemUTC());
    }

    JdbcPay004ScopeResolver(
            NamedParameterJdbcTemplate jdbc,
            Pay004Canonical canonical,
            Clock clock) {
        this.jdbc = jdbc;
        this.canonical = canonical;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Pay004Authority.Scope resolve(Pay004Authority.Subject subject) {
        if (subject == null || !subject.revalidateAt().isAfter(clock.instant())) {
            throw unavailable("PAY-004 authority is absent or stale.");
        }
        try {
            bindTenant(subject.tenantId());
            List<Row> rows = jdbc.query("""
                    SELECT DISTINCT projection.projection_revision,
                           member.legal_entity_id,
                           foundation.payroll_group_id
                      FROM pay_legal_entity_scope_projections projection
                      JOIN pay_legal_entity_scope_members member
                        ON member.tenant_id = projection.tenant_id
                       AND member.projection_id = projection.projection_id
                      JOIN pay_foundation_configurations foundation
                        ON foundation.tenant_id = projection.tenant_id
                       AND foundation.legal_entity_id = member.legal_entity_id
                       AND foundation.lifecycle_state = 'PUBLISHED'
                     WHERE projection.tenant_id = :tenantId
                       AND projection.actor_id = :actorId
                       AND projection.context_scope_key = :scopeKey
                       AND projection.authorization_revision = :decisionRevision
                       AND projection.status = 'ACTIVE'
                       AND projection.valid_from <= :resolvedAt
                       AND (projection.valid_until IS NULL
                            OR projection.valid_until > :resolvedAt)
                     ORDER BY member.legal_entity_id,
                              foundation.payroll_group_id
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", subject.tenantId())
                    .addValue("actorId", subject.actorId())
                    .addValue("scopeKey", subject.contextScopeKey())
                    .addValue("decisionRevision", subject.decisionRevision())
                    .addValue("resolvedAt", OffsetDateTime.ofInstant(
                            clock.instant(), ZoneOffset.UTC)),
                    (result, row) -> new Row(
                            result.getString("projection_revision"),
                            result.getObject("legal_entity_id", UUID.class),
                            result.getObject("payroll_group_id", UUID.class)));
            if (rows.size() != 1) {
                throw unavailable(
                        "PAY-004 requires one exact legal entity and pay group.");
            }
            Row row = rows.getFirst();
            LinkedHashMap<String, Object> binding = new LinkedHashMap<>();
            binding.put("schema", "Pay004OwnerScope.v1");
            binding.put("tenantId", subject.tenantId());
            binding.put("actorId", subject.actorId());
            binding.put("contextScopeKey", subject.contextScopeKey());
            binding.put("populationKind", subject.populationKind());
            binding.put("fieldPolicyKey", subject.fieldPolicyKey());
            binding.put("projectionRevision", row.projectionRevision());
            binding.put("legalEntityId", row.legalEntityId());
            binding.put("payrollGroupId", row.payrollGroupId());
            return new Pay004Authority.Scope(
                    row.legalEntityId(), row.payrollGroupId(),
                    row.projectionRevision(), canonical.digest(binding));
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("PAY-004 owner scope cannot be resolved.");
        }
    }

    private void bindTenant(long tenantId) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource(
                        "tenantId", Long.toString(tenantId)), String.class);
    }

    private BaseException unavailable(String message) {
        return new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    private record Row(
            String projectionRevision,
            UUID legalEntityId,
            UUID payrollGroupId) { }
}
