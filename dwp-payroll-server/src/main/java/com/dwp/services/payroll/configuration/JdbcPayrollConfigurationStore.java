package com.dwp.services.payroll.configuration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CommandReceipt;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CommandType;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.ElementVersion;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaVersion;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.Lifecycle;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PayrollConfigurationPublished;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.ReceiptStatus;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.ResourceType;

/** PostgreSQL adapter for the create-only PAY-006 V6 schema. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.payroll-configuration.wave2.enabled",
        havingValue = "true",
        matchIfMissing = false)
class JdbcPayrollConfigurationStore implements PayrollConfigurationStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcPayrollConfigurationStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ElementVersion> currentElement(long tenantId, UUID elementId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT definition::text
                  FROM pay_element_versions
                 WHERE tenant_id = :tenantId AND element_id = :elementId
                 ORDER BY version DESC
                 LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("elementId", elementId),
                (result, row) -> json(
                        result.getString("definition"), ElementVersion.class))
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ElementVersion> elementVersion(
            long tenantId, UUID elementId, long version) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT definition::text
                  FROM pay_element_versions
                 WHERE tenant_id = :tenantId
                   AND element_id = :elementId
                   AND version = :version
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("elementId", elementId)
                .addValue("version", version),
                (result, row) -> json(
                        result.getString("definition"), ElementVersion.class))
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ElementVersion> currentElements(long tenantId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT DISTINCT ON (element_id) definition::text
                  FROM pay_element_versions
                 WHERE tenant_id = :tenantId
                 ORDER BY element_id, version DESC
                """, new MapSqlParameterSource("tenantId", tenantId),
                (result, row) -> json(
                        result.getString("definition"), ElementVersion.class));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FormulaVersion> currentFormula(long tenantId, UUID formulaId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT definition::text
                  FROM pay_formula_versions
                 WHERE tenant_id = :tenantId AND formula_id = :formulaId
                 ORDER BY version DESC
                 LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("formulaId", formulaId),
                (result, row) -> json(
                        result.getString("definition"), FormulaVersion.class))
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FormulaVersion> formulaVersion(
            long tenantId, UUID formulaId, long version) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT definition::text
                  FROM pay_formula_versions
                 WHERE tenant_id = :tenantId
                   AND formula_id = :formulaId
                   AND version = :version
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("formulaId", formulaId)
                .addValue("version", version),
                (result, row) -> json(
                        result.getString("definition"), FormulaVersion.class))
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FormulaVersion> currentFormulas(long tenantId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT DISTINCT ON (formula_id) definition::text
                  FROM pay_formula_versions
                 WHERE tenant_id = :tenantId
                 ORDER BY formula_id, version DESC
                """, new MapSqlParameterSource("tenantId", tenantId),
                (result, row) -> json(
                        result.getString("definition"), FormulaVersion.class));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CommandReceipt> command(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String originatingAction,
            UUID commandId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT tenant_id, actor_id, receipt_id, command_id, command_type,
                       originating_action, subject_principal_public_id,
                       population_scope_digest, field_policy_revision,
                       purpose_code, authorization_revision,
                       receipt_status, resource_type, resource_id, result_version,
                       correlation_id, request_digest, created_at
                  FROM pay_configuration_command_receipts
                 WHERE tenant_id = :tenantId
                   AND subject_principal_public_id = :subjectPrincipalPublicId
                   AND originating_action = :originatingAction
                   AND command_id = :commandId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("subjectPrincipalPublicId", subjectPrincipalPublicId)
                .addValue("originatingAction", originatingAction)
                .addValue("commandId", commandId),
                (result, row) -> new CommandReceipt(
                        result.getLong("tenant_id"),
                        result.getLong("actor_id"),
                        result.getObject("receipt_id", UUID.class),
                        result.getObject("command_id", UUID.class),
                        CommandType.valueOf(result.getString("command_type")),
                        result.getString("originating_action"),
                        result.getObject("subject_principal_public_id", UUID.class),
                        result.getString("population_scope_digest"),
                        result.getString("field_policy_revision"),
                        result.getString("purpose_code"),
                        result.getString("authorization_revision"),
                        ReceiptStatus.valueOf(result.getString("receipt_status")),
                        result.getString("correlation_id"),
                        result.getString("request_digest"),
                        result.getObject("created_at", OffsetDateTime.class).toInstant(),
                        ResourceType.valueOf(result.getString("resource_type")),
                        result.getObject("resource_id", UUID.class),
                        result.getLong("result_version")))
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PayrollConfigurationPublished> publicationEvent(
            long tenantId, UUID receiptId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT event_payload::text
                  FROM pay_configuration_outbox_events
                 WHERE tenant_id = :tenantId AND receipt_id = :receiptId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId),
                (result, row) -> json(
                        result.getString("event_payload"),
                        PayrollConfigurationPublished.class))
                .stream().findFirst();
    }

    @Override
    @Transactional
    public void saveElement(
            ElementVersion previous,
            ElementVersion updated,
            CommandReceipt receipt) {
        bindTenant(updated.tenantId());
        lock(updated.tenantId(), updated.elementId());
        assertCurrentElement(previous, updated);
        try {
            jdbc.update("""
                    INSERT INTO pay_element_versions (
                        tenant_id, element_id, version, lifecycle_state,
                        legal_entity_id, payroll_group_id, element_code,
                        definition, authored_by, command_id, recorded_at)
                    VALUES (
                        :tenantId, :elementId, :version, :state,
                        :legalEntityId, :payrollGroupId, :elementCode,
                        CAST(:definition AS jsonb), :authorId, :commandId, :recordedAt)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", updated.tenantId())
                    .addValue("elementId", updated.elementId())
                    .addValue("version", updated.version())
                    .addValue("state", updated.status().name())
                    .addValue("legalEntityId", updated.legalEntityId())
                    .addValue("payrollGroupId", updated.payrollGroupId())
                    .addValue("elementCode", updated.elementCode())
                    .addValue("definition", json(updated))
                    .addValue("authorId", updated.authorId())
                    .addValue("commandId", updated.commandId())
                    .addValue("recordedAt", OffsetDateTime.ofInstant(
                            updated.recordedAt(), ZoneOffset.UTC)));
            insertReceipt(receipt);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Payroll element version or command changed.", exception);
        }
    }

    @Override
    @Transactional
    public void saveFormula(
            FormulaVersion previous,
            FormulaVersion updated,
            CommandReceipt receipt,
            PayrollConfigurationPublished event) {
        bindTenant(updated.tenantId());
        lock(updated.tenantId(), updated.formulaId());
        assertCurrentFormula(previous, updated);
        try {
            jdbc.update("""
                    INSERT INTO pay_formula_versions (
                        tenant_id, formula_id, version, lifecycle_state,
                        legal_entity_id, payroll_group_id, formula_code,
                        definition, ast_digest, validation_digest,
                        authored_by, published_by, command_id, recorded_at)
                    VALUES (
                        :tenantId, :formulaId, :version, :state,
                        :legalEntityId, :payrollGroupId, :formulaCode,
                        CAST(:definition AS jsonb), :astDigest, :validationDigest,
                        :authorId, :publisherId, :commandId, :recordedAt)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", updated.tenantId())
                    .addValue("formulaId", updated.formulaId())
                    .addValue("version", updated.version())
                    .addValue("state", updated.status().name())
                    .addValue("legalEntityId", updated.legalEntityId())
                    .addValue("payrollGroupId", updated.payrollGroupId())
                    .addValue("formulaCode", updated.formulaCode())
                    .addValue("definition", json(updated))
                    .addValue("astDigest", new PayrollConfigurationCanonical(
                            objectMapper).digest(updated.ast()))
                    .addValue("validationDigest", updated.validation() == null
                            ? null : updated.validation().validationDigest())
                    .addValue("authorId", updated.authorId())
                    .addValue("publisherId", updated.publisherId())
                    .addValue("commandId", updated.commandId())
                    .addValue("recordedAt", OffsetDateTime.ofInstant(
                            updated.recordedAt(), ZoneOffset.UTC)));
            insertReceipt(receipt);
            if (event != null) insertEvent(receipt.receiptId(), updated.commandId(), event);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Payroll formula version or command changed.", exception);
        }
    }

    private void insertReceipt(CommandReceipt receipt) {
        jdbc.update("""
                INSERT INTO pay_configuration_command_receipts (
                    tenant_id, actor_id, receipt_id, command_id, command_type,
                    originating_action, subject_principal_public_id,
                    population_scope_digest, field_policy_revision,
                    purpose_code, authorization_revision, receipt_status,
                    correlation_id, request_digest, created_at,
                    resource_type, resource_id, result_version)
                VALUES (
                    :tenantId, :actorId, :receiptId, :commandId, :commandType,
                    :originatingAction, :subjectPrincipalPublicId,
                    :populationScopeDigest, :fieldPolicyRevision,
                    :purposeCode, :authorizationRevision, :status,
                    :correlationId, :requestDigest, :createdAt,
                    :resourceType, :resourceId, :resultVersion)
                """, new MapSqlParameterSource()
                .addValue("tenantId", receipt.tenantId())
                .addValue("actorId", receipt.actorId())
                .addValue("receiptId", receipt.receiptId())
                .addValue("commandId", receipt.commandId())
                .addValue("commandType", receipt.commandType().name())
                .addValue("originatingAction", receipt.originatingAction())
                .addValue("subjectPrincipalPublicId", receipt.subjectPrincipalPublicId())
                .addValue("populationScopeDigest", receipt.populationScopeDigest())
                .addValue("fieldPolicyRevision", receipt.fieldPolicyRevision())
                .addValue("purposeCode", receipt.purposeCode())
                .addValue("authorizationRevision", receipt.authorizationRevision())
                .addValue("status", receipt.status().name())
                .addValue("correlationId", receipt.correlationId())
                .addValue("requestDigest", receipt.requestDigest())
                .addValue("createdAt", OffsetDateTime.ofInstant(
                        receipt.createdAt(), ZoneOffset.UTC))
                .addValue("resourceType", receipt.resourceType().name())
                .addValue("resourceId", receipt.resourceId())
                .addValue("resultVersion", receipt.resultVersion()));
    }

    private void insertEvent(
            UUID receiptId,
            UUID commandId,
            PayrollConfigurationPublished event) {
        jdbc.update("""
                INSERT INTO pay_configuration_outbox_events (
                    tenant_id, event_id, receipt_id, command_id, aggregate_id,
                    aggregate_sequence, event_type, schema_version,
                    correlation_id, event_payload, occurred_at)
                VALUES (
                    :tenantId, :eventId, :receiptId, :commandId, :aggregateId,
                    :aggregateSequence, :eventType, :schemaVersion,
                    :correlationId, CAST(:eventPayload AS jsonb), :occurredAt)
                """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", event.id())
                .addValue("receiptId", receiptId)
                .addValue("commandId", commandId)
                .addValue("aggregateId", event.aggregateId())
                .addValue("aggregateSequence", event.aggregateSequence())
                .addValue("eventType", event.type())
                .addValue("schemaVersion", event.schemaVersion())
                .addValue("correlationId", event.correlationId())
                .addValue("eventPayload", json(event))
                .addValue("occurredAt", OffsetDateTime.ofInstant(
                        event.time(), ZoneOffset.UTC)));
    }

    private void assertCurrentElement(
            ElementVersion previous, ElementVersion updated) {
        Optional<ElementVersion> stored = currentElement(
                updated.tenantId(), updated.elementId());
        if (previous == null ? stored.isPresent()
                : stored.isEmpty() || stored.get().version() != previous.version()) {
            throw conflict("Payroll element version changed. Refresh and retry.", null);
        }
    }

    private void assertCurrentFormula(
            FormulaVersion previous, FormulaVersion updated) {
        Optional<FormulaVersion> stored = currentFormula(
                updated.tenantId(), updated.formulaId());
        if (previous == null ? stored.isPresent()
                : stored.isEmpty() || stored.get().version() != previous.version()) {
            throw conflict("Payroll formula version changed. Refresh and retry.", null);
        }
    }

    private void lock(long tenantId, UUID resourceId) {
        long key = tenantId
                ^ resourceId.getMostSignificantBits()
                ^ resourceId.getLeastSignificantBits();
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(:lockKey)",
                new MapSqlParameterSource("lockKey", key));
    }

    private void bindTenant(long tenantId) {
        if (tenantId <= 0) {
            throw new BaseException(ErrorCode.TENANT_MISSING, "A positive tenant is required.");
        }
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", Long.toString(tenantId)),
                String.class);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Payroll configuration cannot be serialized.", exception);
        }
    }

    private <T> T json(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored payroll configuration is invalid.", exception);
        }
    }

    private BaseException conflict(String message, RuntimeException cause) {
        BaseException exception = new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
        if (cause != null) exception.initCause(cause);
        return exception;
    }
}
