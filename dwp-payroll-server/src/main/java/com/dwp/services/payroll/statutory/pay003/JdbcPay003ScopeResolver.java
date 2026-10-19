package com.dwp.services.payroll.statutory.pay003;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_STATUTORY_INSURANCE.enabled",
        havingValue = "true",
        matchIfMissing = false)
class JdbcPay003ScopeResolver implements Pay003ScopeResolver {

    private final NamedParameterJdbcTemplate jdbc;
    private final Pay003Canonical canonical;
    private final Clock clock;

    JdbcPay003ScopeResolver(
            NamedParameterJdbcTemplate jdbc, Pay003Canonical canonical) {
        this(jdbc, canonical, Clock.systemUTC());
    }

    JdbcPay003ScopeResolver(
            NamedParameterJdbcTemplate jdbc,
            Pay003Canonical canonical,
            Clock clock) {
        this.jdbc = jdbc;
        this.canonical = canonical;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Pay003Authority.Scope resolve(Pay003Authority.Subject subject) {
        if (subject == null || !subject.revalidateAt().isAfter(clock.instant())) {
            throw unavailable("PAY-003 authorization evidence is absent or stale.");
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
                        "PAY-003 requires exactly one current legal entity and pay group.");
            }
            Row row = rows.getFirst();
            Map<String, Object> scopeBinding = new java.util.LinkedHashMap<>();
            scopeBinding.put("schema", "Pay003OwnerScope.v1");
            scopeBinding.put("tenantId", subject.tenantId());
            scopeBinding.put("actorId", subject.actorId());
            scopeBinding.put("contextScopeKey", subject.contextScopeKey());
            scopeBinding.put("populationKind", subject.populationKind());
            scopeBinding.put("fieldPolicyKey", subject.fieldPolicyKey());
            scopeBinding.put("projectionRevision", row.projectionRevision());
            scopeBinding.put("legalEntityId", row.legalEntityId());
            scopeBinding.put("payrollGroupId", row.payrollGroupId());
            String digest = canonical.digest(scopeBinding);
            return new Pay003Authority.Scope(
                    row.legalEntityId(), row.payrollGroupId(),
                    row.projectionRevision(), digest);
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("PAY-003 owner scope cannot be resolved.");
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
            UUID payrollGroupId) {
    }
}
