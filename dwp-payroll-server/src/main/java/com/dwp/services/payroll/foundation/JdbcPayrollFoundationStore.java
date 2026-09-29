package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandReceipt;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandType;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ConfigurationSnapshot;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationDefinition;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.Lifecycle;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ReceiptStatus;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.SimulationReport;

/** PostgreSQL adapter for the migration-leased payroll foundation schema. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.payroll-foundation.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
class JdbcPayrollFoundationStore implements PayrollFoundationStore {

    private static final String CURRENT_SELECT = """
            SELECT c.tenant_id, c.configuration_id, c.current_version AS version,
                   c.lifecycle_state, v.definition::text AS definition,
                   v.authored_by AS author_id, c.publisher_id, c.created_at, c.updated_at,
                   c.simulation_report::text AS simulation_report, c.last_command_id
              FROM pay_foundation_configurations c
              JOIN pay_foundation_versions v
                ON v.tenant_id = c.tenant_id
               AND v.configuration_id = c.configuration_id
               AND v.version = c.current_version
            """;

    private static final String VERSION_SELECT = """
            SELECT v.tenant_id, v.configuration_id, v.version, v.lifecycle_state,
                   v.definition::text AS definition, v.authored_by AS author_id,
                   v.published_by AS publisher_id, c.created_at,
                   v.recorded_at AS updated_at,
                   v.simulation_report::text AS simulation_report,
                   v.command_id AS last_command_id
              FROM pay_foundation_versions v
              JOIN pay_foundation_configurations c
                ON c.tenant_id = v.tenant_id
               AND c.configuration_id = v.configuration_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<ConfigurationSnapshot> snapshotMapper = this::mapSnapshot;

    JdbcPayrollFoundationStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigurationSnapshot> current(long tenantId, UUID configurationId) {
        bindTenant(tenantId);
        return jdbc.query(
                CURRENT_SELECT + " WHERE c.tenant_id = :tenantId AND c.configuration_id = :id",
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("id", configurationId),
                snapshotMapper).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConfigurationSnapshot> currentForTenant(long tenantId) {
        bindTenant(tenantId);
        return jdbc.query(
                CURRENT_SELECT + " WHERE c.tenant_id = :tenantId ORDER BY c.updated_at DESC",
                new MapSqlParameterSource("tenantId", tenantId), snapshotMapper);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConfigurationSnapshot> versions(long tenantId, UUID configurationId) {
        bindTenant(tenantId);
        return jdbc.query(VERSION_SELECT + """
                 WHERE v.tenant_id = :tenantId AND v.configuration_id = :id
                 ORDER BY v.version DESC
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", configurationId), snapshotMapper);
    }

    @Override
    @Transactional
    public void save(
            ConfigurationSnapshot previous,
            ConfigurationSnapshot updated,
            CommandReceipt command) {
        bindTenant(updated.tenantId());
        lockOverlapKey(updated);
        rejectOverlap(updated);
        try {
            if (previous == null) {
                insertConfiguration(updated);
                insertVersion(updated);
            } else {
                updateConfiguration(previous, updated);
                if (updated.version() > previous.version()) {
                    insertVersion(updated);
                }
            }
            insertAudit(updated, command);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Payroll configuration changed or violates a persistence invariant.", exception);
        }
    }

    @Override
    @Transactional
    public ReceiptReservation reserve(CommandReceipt receipt) {
        bindTenant(receipt.tenantId());
        String sql = """
                INSERT INTO pay_foundation_command_receipts (
                    tenant_id, actor_id, command_id, command_type, receipt_status,
                    request_digest, configuration_id, result_version,
                    reversal_of_command_id, failure_code, correlation_id,
                    authority_purpose, legal_entity_scope_digest,
                    policy_revision, authorization_revision,
                    created_at, completed_at)
                VALUES (:tenantId, :actorId, :commandId, :commandType, :status,
                    :requestDigest, :configurationId, :resultVersion,
                    :reversalOf, :failureCode, :correlationId,
                    :authorityPurpose, :legalEntityScopeDigest,
                    :policyRevision, :authorizationRevision,
                    :createdAt, :completedAt)
                ON CONFLICT (tenant_id, command_id) DO NOTHING
                """;
        boolean created = jdbc.update(sql, receiptParameters(receipt)) == 1;
        CommandReceipt stored = receipt(receipt.tenantId(), receipt.commandId())
                .orElseThrow(() -> new IllegalStateException("receipt reservation was not observable"));
        return new ReceiptReservation(stored, created);
    }

    @Override
    @Transactional
    public void replaceReceipt(CommandReceipt receipt) {
        bindTenant(receipt.tenantId());
        String sql = """
                UPDATE pay_foundation_command_receipts
                   SET receipt_status = :status,
                       configuration_id = :configurationId,
                       result_version = :resultVersion,
                       failure_code = :failureCode,
                       completed_at = :completedAt
                 WHERE tenant_id = :tenantId AND command_id = :commandId
                   AND receipt_status IN ('PENDING', 'RESULT_UNKNOWN')
                """;
        if (jdbc.update(sql, receiptParameters(receipt)) != 1) {
            throw new IllegalStateException(
                    "command receipt is missing or already terminal");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CommandReceipt> receipt(long tenantId, UUID commandId) {
        bindTenant(tenantId);
        String sql = """
                SELECT tenant_id, actor_id, command_id, command_type, receipt_status,
                       request_digest, configuration_id, result_version,
                       reversal_of_command_id, failure_code, correlation_id,
                       authority_purpose, legal_entity_scope_digest,
                       policy_revision, authorization_revision,
                       created_at, completed_at
                  FROM pay_foundation_command_receipts
                 WHERE tenant_id = :tenantId AND command_id = :commandId
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("commandId", commandId), this::mapReceipt).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigurationSnapshot> byCommand(long tenantId, UUID commandId) {
        bindTenant(tenantId);
        return jdbc.query(
                VERSION_SELECT
                        + " WHERE v.tenant_id = :tenantId AND v.command_id = :commandId",
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("commandId", commandId),
                snapshotMapper).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigurationSnapshot> version(
            long tenantId, UUID configurationId, long version) {
        bindTenant(tenantId);
        return jdbc.query(
                VERSION_SELECT + """
                 WHERE v.tenant_id = :tenantId
                   AND v.configuration_id = :configurationId
                   AND v.version = :version
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("configurationId", configurationId)
                        .addValue("version", version),
                snapshotMapper).stream().findFirst();
    }

    private void lockOverlapKey(ConfigurationSnapshot snapshot) {
        long lockKey = snapshot.tenantId()
                ^ snapshot.definition().payrollGroup().id().getMostSignificantBits()
                ^ snapshot.definition().payrollGroup().id().getLeastSignificantBits();
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(:lockKey)",
                new MapSqlParameterSource("lockKey", lockKey));
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

    private void rejectOverlap(ConfigurationSnapshot snapshot) {
        LocalDate end = snapshot.definition().effectivePeriod().endsOn();
        String sql = """
                SELECT COUNT(*)
                  FROM pay_foundation_configurations
                 WHERE tenant_id = :tenantId
                   AND payroll_group_id = :groupId
                   AND configuration_id <> :configurationId
                   AND lifecycle_state <> 'REVERSED'
                   AND (effective_to IS NULL OR effective_to >= :effectiveFrom)
                """ + (end == null ? "" : " AND effective_from <= :effectiveTo");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", snapshot.tenantId())
                .addValue("groupId", snapshot.definition().payrollGroup().id())
                .addValue("configurationId", snapshot.configurationId())
                .addValue("effectiveFrom", snapshot.definition().effectivePeriod().startsOn());
        if (end != null) {
            parameters.addValue("effectiveTo", end);
        }
        Long count = jdbc.queryForObject(sql, parameters, Long.class);
        if (count == null || count > 0) {
            throw conflict("Payroll group effective periods cannot overlap.", null);
        }
    }

    private void insertConfiguration(ConfigurationSnapshot snapshot) {
        String sql = """
                INSERT INTO pay_foundation_configurations (
                    tenant_id, configuration_id, current_version, lifecycle_state,
                    legal_entity_id, payroll_group_id, effective_from, effective_to,
                    author_id, publisher_id, simulation_report, last_command_id,
                    created_at, updated_at)
                VALUES (:tenantId, :configurationId, :version, :state,
                    :legalEntityId, :groupId, :effectiveFrom, :effectiveTo,
                    :authorId, :publisherId, CAST(:simulation AS jsonb), :lastCommandId,
                    :createdAt, :updatedAt)
                """;
        jdbc.update(sql, snapshotParameters(snapshot));
    }

    private void updateConfiguration(ConfigurationSnapshot previous, ConfigurationSnapshot updated) {
        String sql = """
                UPDATE pay_foundation_configurations
                   SET current_version = :version,
                       lifecycle_state = :state,
                       legal_entity_id = :legalEntityId,
                       payroll_group_id = :groupId,
                       effective_from = :effectiveFrom,
                       effective_to = :effectiveTo,
                       author_id = :authorId,
                       publisher_id = :publisherId,
                       simulation_report = CAST(:simulation AS jsonb),
                       last_command_id = :lastCommandId,
                       updated_at = :updatedAt
                 WHERE tenant_id = :tenantId
                   AND configuration_id = :configurationId
                   AND current_version = :previousVersion
                   AND last_command_id = :previousCommandId
                """;
        MapSqlParameterSource parameters = snapshotParameters(updated)
                .addValue("previousVersion", previous.version())
                .addValue("previousCommandId", previous.lastCommandId());
        if (jdbc.update(sql, parameters) != 1) {
            throw conflict("Payroll configuration version changed. Refresh and retry.", null);
        }
    }

    private void insertVersion(ConfigurationSnapshot snapshot) {
        String sql = """
                INSERT INTO pay_foundation_versions (
                    tenant_id, configuration_id, version, definition,
                    definition_digest, dependency_digest, lifecycle_state, simulation_report,
                    authored_by, published_by, command_id, recorded_at)
                VALUES (:tenantId, :configurationId, :version, CAST(:definition AS jsonb),
                    :definitionDigest, :dependencyDigest, :versionState,
                    CAST(:simulation AS jsonb),
                    :authorId, :publisherId, :lastCommandId, :updatedAt)
                """;
        jdbc.update(sql, snapshotParameters(snapshot)
                .addValue("definition", json(snapshot.definition()))
                .addValue("definitionDigest",
                        PayrollFoundationCanonical.definitionDigest(snapshot.definition()))
                .addValue("dependencyDigest",
                        PayrollFoundationCanonical.dependencyDigest(snapshot.definition()))
                .addValue("versionState", snapshot.status().name()));
    }

    private void insertAudit(ConfigurationSnapshot snapshot, CommandReceipt command) {
        String sql = """
                INSERT INTO pay_foundation_audit_events (
                    tenant_id, event_id, configuration_id, configuration_version,
                    command_id, event_type, actor_id, subject_id, correlation_id,
                    authority_purpose, legal_entity_scope_digest,
                    policy_revision, authorization_revision,
                    occurred_at, event_payload)
                VALUES (:tenantId, :eventId, :configurationId, :version,
                    :lastCommandId, :eventType, :actorId, :subjectId, :correlationId,
                    :authorityPurpose, :legalEntityScopeDigest,
                    :policyRevision, :authorizationRevision,
                    :updatedAt, CAST(:payload AS jsonb))
                """;
        jdbc.update(sql, snapshotParameters(snapshot)
                .addValue("eventId", UUID.randomUUID())
                .addValue("eventType", "FOUNDATION_" + snapshot.status().name())
                .addValue("actorId", command.actorId())
                .addValue("subjectId", snapshot.authorId())
                .addValue("correlationId", command.correlationId())
                .addValue("authorityPurpose", command.authorityPurpose())
                .addValue("legalEntityScopeDigest", command.legalEntityScopeDigest())
                .addValue("policyRevision", command.policyRevision())
                .addValue("authorizationRevision", command.authorizationRevision())
                .addValue("payload", json(Map.of(
                        "lifecycle", snapshot.status().name(),
                        "version", snapshot.version(),
                        "subjectId", snapshot.authorId()))));
    }

    private MapSqlParameterSource snapshotParameters(ConfigurationSnapshot snapshot) {
        return new MapSqlParameterSource()
                .addValue("tenantId", snapshot.tenantId())
                .addValue("configurationId", snapshot.configurationId())
                .addValue("version", snapshot.version())
                .addValue("state", snapshot.status().name())
                .addValue("legalEntityId", snapshot.definition().legalEntity().id())
                .addValue("groupId", snapshot.definition().payrollGroup().id())
                .addValue("effectiveFrom", snapshot.definition().effectivePeriod().startsOn())
                .addValue("effectiveTo", snapshot.definition().effectivePeriod().endsOn())
                .addValue("authorId", snapshot.authorId())
                .addValue("publisherId", snapshot.publisherId())
                .addValue("simulation", snapshot.simulation() == null
                        ? null : json(snapshot.simulation()))
                .addValue("lastCommandId", snapshot.lastCommandId())
                .addValue("createdAt", timestamp(snapshot.createdAt()))
                .addValue("updatedAt", timestamp(snapshot.updatedAt()));
    }

    private MapSqlParameterSource receiptParameters(CommandReceipt receipt) {
        return new MapSqlParameterSource()
                .addValue("tenantId", receipt.tenantId())
                .addValue("actorId", receipt.actorId())
                .addValue("commandId", receipt.commandId())
                .addValue("commandType", receipt.commandType().name())
                .addValue("status", receipt.status().name())
                .addValue("requestDigest", receipt.requestDigest())
                .addValue("configurationId", receipt.configurationId())
                .addValue("resultVersion", receipt.resultVersion())
                .addValue("reversalOf", receipt.reversalOfCommandId())
                .addValue("failureCode", receipt.failureCode())
                .addValue("correlationId", receipt.correlationId())
                .addValue("authorityPurpose", receipt.authorityPurpose())
                .addValue("legalEntityScopeDigest", receipt.legalEntityScopeDigest())
                .addValue("policyRevision", receipt.policyRevision())
                .addValue("authorizationRevision", receipt.authorizationRevision())
                .addValue("createdAt", timestamp(receipt.createdAt()))
                .addValue("completedAt", timestamp(receipt.completedAt()));
    }

    private ConfigurationSnapshot mapSnapshot(ResultSet row, int rowNumber) throws SQLException {
        FoundationDefinition definition = fromJson(
                row.getString("definition"), FoundationDefinition.class);
        String simulationJson = row.getString("simulation_report");
        SimulationReport simulation = simulationJson == null ? null
                : fromJson(simulationJson, SimulationReport.class);
        long publisher = row.getLong("publisher_id");
        return new ConfigurationSnapshot(
                row.getLong("tenant_id"),
                row.getObject("configuration_id", UUID.class),
                row.getLong("version"),
                Lifecycle.valueOf(row.getString("lifecycle_state")),
                definition,
                row.getLong("author_id"),
                row.wasNull() ? null : publisher,
                row.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", java.time.OffsetDateTime.class).toInstant(),
                simulation,
                row.getObject("last_command_id", UUID.class));
    }

    private CommandReceipt mapReceipt(ResultSet row, int rowNumber) throws SQLException {
        long resultVersion = row.getLong("result_version");
        Long nullableVersion = row.wasNull() ? null : resultVersion;
        java.time.OffsetDateTime completed = row.getObject(
                "completed_at", java.time.OffsetDateTime.class);
        return new CommandReceipt(
                row.getLong("tenant_id"),
                row.getLong("actor_id"),
                row.getObject("command_id", UUID.class),
                CommandType.valueOf(row.getString("command_type")),
                ReceiptStatus.valueOf(row.getString("receipt_status")),
                row.getString("request_digest"),
                row.getObject("configuration_id", UUID.class),
                nullableVersion,
                row.getObject("reversal_of_command_id", UUID.class),
                row.getString("failure_code"),
                row.getString("correlation_id"),
                row.getString("authority_purpose"),
                row.getString("legal_entity_scope_digest"),
                row.getString("policy_revision"),
                row.getString("authorization_revision"),
                row.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Payroll foundation value is not serializable", exception);
        }
    }

    private <T> T fromJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored payroll foundation value is invalid", exception);
        }
    }

    private OffsetDateTime timestamp(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private BaseException conflict(String message, RuntimeException cause) {
        return cause == null
                ? new BaseException(ErrorCode.RESOURCE_CONFLICT, message)
                : new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }
}
