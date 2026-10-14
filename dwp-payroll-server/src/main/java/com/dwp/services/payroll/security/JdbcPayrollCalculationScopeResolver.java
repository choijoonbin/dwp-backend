package com.dwp.services.payroll.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
class JdbcPayrollCalculationScopeResolver implements PayrollCalculationScopeResolver {

    private final NamedParameterJdbcTemplate jdbc;
    private final PayrollV41PepRegistry registry;
    private final PayrollFieldProjectionGuard projection;
    private final Clock clock;

    @Autowired
    JdbcPayrollCalculationScopeResolver(
            NamedParameterJdbcTemplate jdbc,
            PayrollV41PepRegistry registry,
            PayrollFieldProjectionGuard projection) {
        this(jdbc, registry, projection, Clock.systemUTC());
    }

    JdbcPayrollCalculationScopeResolver(
            NamedParameterJdbcTemplate jdbc,
            PayrollV41PepRegistry registry,
            PayrollFieldProjectionGuard projection,
            Clock clock) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.projection = projection;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollCalculationAuthority.Scope resolve(
            PayrollCalculationAuthority.Subject subject) {
        if (subject == null || !subject.revalidateAt().isAfter(clock.instant())) {
            throw unavailable("Payroll calculation authority is absent or stale.");
        }
        if (!"LEGAL_ENTITY".equals(subject.populationKind())) {
            throw unavailable("The current Payroll scope projection cannot resolve an exact "
                    + subject.populationKind() + " population tuple.");
        }
        try {
            bindTenant(subject.tenantId());
            List<ScopeRow> rows = jdbc.query("""
                    SELECT DISTINCT p.projection_revision, m.legal_entity_id,
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
                    (result, row) -> new ScopeRow(
                            result.getString("projection_revision"),
                            result.getObject("legal_entity_id", UUID.class),
                            result.getObject("payroll_group_id", UUID.class)));
            if (rows.isEmpty()) {
                throw unavailable("No current Payroll-owned target population exists.");
            }
            Set<String> revisions = rows.stream().map(ScopeRow::projectionRevision)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (revisions.size() != 1) {
                throw unavailable("Payroll target population revision is ambiguous.");
            }
            Set<PayrollCalculationAuthority.PopulationTuple> populations = rows.stream()
                    .map(row -> new PayrollCalculationAuthority.PopulationTuple(
                            row.legalEntityId(), row.payrollGroupId(),
                            subject.populationKind(), subject.contextScopeKey()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            String revision = revisions.iterator().next();
            String material = subject.contextScopeKey() + "|" + subject.populationKind()
                    + "|" + revision + "|"
                    + populations.stream()
                    .sorted(java.util.Comparator
                            .comparing(PayrollCalculationAuthority.PopulationTuple
                                    ::legalEntityId)
                            .thenComparing(PayrollCalculationAuthority.PopulationTuple
                                    ::payrollGroupId))
                    .map(population -> population.legalEntityId() + "/"
                            + population.payrollGroupId() + "/"
                            + population.populationKind() + "/"
                            + population.contextScopeKey())
                    .collect(Collectors.joining(","));
            return new PayrollCalculationAuthority.Scope(
                    populations, revision, sha256(material));
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Payroll target population cannot be resolved.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollCalculationAuthority.Scope resolveReceipt(
            PayrollCalculationAuthority.Subject subject, UUID receiptId) {
        if (subject == null || receiptId == null
                || subject.routeAction()
                != PayrollCalculationAuthority.Action.RECEIPT_VIEW
                || !"ORIGINATING_COMMAND_SCOPE".equals(subject.populationKind())
                || !"COMMAND_STATUS".equals(subject.purposeCode())
                || !subject.revalidateAt().isAfter(clock.instant())) {
            throw opaque();
        }
        try {
            bindTenant(subject.tenantId());
            ReceiptOrigin origin = jdbc.query("""
                    WITH run_head AS (
                        SELECT DISTINCT ON (tenant_id, run_id)
                               tenant_id, run_id, legal_entity_id, payroll_group_id
                          FROM pay_payroll_runs
                         WHERE tenant_id = :tenantId
                         ORDER BY tenant_id, run_id, run_revision DESC
                    )
                    SELECT receipt.subject_principal_public_id,
                           receipt.command_type, receipt.originating_action,
                           receipt.population_kind,
                           receipt.population_scope_digest,
                           receipt.context_scope_key,
                           receipt.purpose_code,
                           receipt.field_policy_revision,
                           receipt.authorization_revision,
                           run.legal_entity_id, run.payroll_group_id
                      FROM pay_command_receipts receipt
                      JOIN run_head run
                        ON run.tenant_id = receipt.tenant_id
                       AND run.run_id = receipt.run_id
                     WHERE receipt.tenant_id = :tenantId
                       AND receipt.receipt_id = :receiptId
                       AND receipt.status_sequence = 1
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", subject.tenantId())
                    .addValue("receiptId", receiptId),
                    (result, row) -> new ReceiptOrigin(
                            result.getObject("subject_principal_public_id", UUID.class),
                            result.getString("command_type"),
                            result.getString("originating_action"),
                            result.getString("population_kind"),
                            result.getString("population_scope_digest"),
                            result.getString("context_scope_key"),
                            result.getString("purpose_code"),
                            result.getLong("field_policy_revision"),
                            result.getLong("authorization_revision"),
                            result.getObject("legal_entity_id", UUID.class),
                            result.getObject("payroll_group_id", UUID.class)))
                    .stream().findFirst().orElseThrow(this::opaque);
            PayrollCalculationRoute originRoute = originatingRoute(
                    origin.commandType());
            PayrollV41PepRegistry.Binding originBinding = registry.binding(originRoute);
            if (!origin.subjectPrincipalPublicId().equals(
                    subject.subjectPrincipalPublicId())
                    || !origin.contextScopeKey().equals(subject.contextScopeKey())
                    || subject.fieldPolicyRevision() < origin.fieldPolicyRevision()
                    || subject.authorizationRevision() < origin.authorizationRevision()
                    || !origin.originatingAction().equals(
                    originatingAction(origin.commandType()))
                    || !origin.purposeCode().equals(originRoute.purposeCode())
                    || !originBinding.authorizedBy(subject.permissions())
                    || !originBinding.activeAccessModes().contains(
                    subject.accessMode())
                    || !"LEGAL_ENTITY".equals(origin.populationKind())) {
                throw opaque();
            }
            PayrollCalculationAuthority.Subject originatingSubject =
                    new PayrollCalculationAuthority.Subject(
                            subject.tenantId(), subject.actorId(),
                            subject.subjectPrincipalPublicId(),
                            originRoute.action(), originRoute.routeContractKey(),
                            originBinding.matchedCapabilityContract(
                                    subject.permissions()),
                            origin.purposeCode(), subject.contextKey(),
                            origin.contextScopeKey(), origin.populationKind(),
                            subject.fieldPolicyRevision(), subject.authorizationRevision(),
                            subject.decisionRevision(), subject.revalidateAt(),
                            subject.accessMode(), subject.permissions());
            projection.requireFull(originatingSubject, originRoute);
            PayrollCalculationAuthority.Scope current = resolve(originatingSubject);
            PayrollCalculationAuthority.PopulationTuple exact =
                    new PayrollCalculationAuthority.PopulationTuple(
                            origin.legalEntityId(), origin.payrollGroupId(),
                            origin.populationKind(), origin.contextScopeKey());
            if (!current.populations().contains(exact)
                    || !current.scopeDigest().equals(origin.populationScopeDigest())) {
                throw opaque();
            }
            projection.requireFull(subject, PayrollCalculationRoute.RECEIPT_VIEW);
            Set<PayrollCalculationAuthority.PopulationTuple> receiptPopulations =
                    current.populations().stream()
                            .map(population ->
                                    new PayrollCalculationAuthority.PopulationTuple(
                                            population.legalEntityId(),
                                            population.payrollGroupId(),
                                            subject.populationKind(),
                                            subject.contextScopeKey()))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            return new PayrollCalculationAuthority.Scope(
                    receiptPopulations, current.projectionRevision(),
                    current.scopeDigest());
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.NOT_FOUND) throw exception;
            throw opaque();
        } catch (RuntimeException exception) {
            throw opaque();
        }
    }

    private PayrollCalculationRoute originatingRoute(String commandType) {
        return switch (commandType) {
            case "RUN_CALCULATE" -> PayrollCalculationRoute.CALCULATE;
            case "RUN_APPROVE" -> PayrollCalculationRoute.APPROVE;
            case "RUN_FINALIZE" -> PayrollCalculationRoute.FINALIZE;
            default -> throw opaque();
        };
    }

    private String originatingAction(String commandType) {
        return switch (commandType) {
            case "RUN_CALCULATE" -> "PAY_RUN_CALCULATE";
            case "RUN_APPROVE" -> "PAY_RUN_APPROVE";
            case "RUN_FINALIZE" -> "PAY_RUN_FINALIZE";
            default -> throw opaque();
        };
    }

    private void bindTenant(long tenantId) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", Long.toString(tenantId)),
                String.class);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    private BaseException opaque() {
        return new BaseException(ErrorCode.NOT_FOUND,
                "The payroll resource was not found.");
    }

    private record ScopeRow(
            String projectionRevision,
            UUID legalEntityId,
            UUID payrollGroupId) {
    }

    private record ReceiptOrigin(
            UUID subjectPrincipalPublicId,
            String commandType,
            String originatingAction,
            String populationKind,
            String populationScopeDigest,
            String contextScopeKey,
            String purposeCode,
            long fieldPolicyRevision,
            long authorizationRevision,
            UUID legalEntityId,
            UUID payrollGroupId) {
    }
}
