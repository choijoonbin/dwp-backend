package com.dwp.services.provider.resourcegovernance;

import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AppendLedgerEntryRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateResourceCommitmentChangeRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ResourceCommitmentChangeDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.UpsertCommitmentRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.CommitmentRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.InternalEvidenceFreshnessRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.LedgerRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.LedgerTotalsRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ResourceCommitmentChangeRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ResourceCommitmentJdbcRepository {
    private static final String COMMITMENT_SELECT = """
            SELECT commitment.provider_tenant_id, tenant.tenant_key,
                   tenant.display_name AS tenant_display_name,
                   commitment.resource_key,
                   CASE WHEN active_override.change_request_id IS NULL THEN commitment.unit
                        ELSE active_override.proposed_definition ->> 'unit' END AS unit,
                   CASE WHEN active_override.change_request_id IS NULL THEN commitment.quota_limit
                        ELSE NULLIF(active_override.proposed_definition ->> 'quotaLimit', '')::numeric END AS quota_limit,
                   CASE WHEN active_override.change_request_id IS NULL THEN commitment.budget_limit
                        ELSE NULLIF(active_override.proposed_definition ->> 'budgetLimit', '')::numeric END AS budget_limit,
                   CASE WHEN active_override.change_request_id IS NULL THEN commitment.currency_code
                        ELSE active_override.proposed_definition ->> 'currencyCode' END AS currency_code,
                   CASE WHEN active_override.change_request_id IS NULL THEN commitment.lifecycle_state
                        ELSE active_override.proposed_definition ->> 'lifecycleState' END AS lifecycle_state,
                   commitment.source_system, commitment.external_feed_state, commitment.version,
                   CASE WHEN active_override.change_request_id IS NULL THEN commitment.control_mode
                        ELSE active_override.proposed_definition ->> 'controlMode' END AS control_mode,
                   commitment.control_scope,
                   CASE WHEN active_override.change_request_id IS NULL THEN commitment.control_period_start
                        ELSE (active_override.proposed_definition ->> 'controlPeriodStartsAt')::timestamptz
                   END AS control_period_start,
                   CASE WHEN active_override.change_request_id IS NULL THEN commitment.control_period_end
                        ELSE (active_override.proposed_definition ->> 'controlPeriodEndsAt')::timestamptz
                   END AS control_period_end,
                   active_override.change_request_id AS active_override_id,
                   active_override.override_expires_at AS active_override_expires_at,
                   commitment.updated_at
              FROM prv_tenant_resource_commitments commitment
              JOIN prv_tenants tenant
                ON tenant.provider_tenant_id = commitment.provider_tenant_id
              LEFT JOIN LATERAL (
                    SELECT change.change_request_id, change.proposed_definition,
                           change.override_expires_at
                      FROM prv_resource_commitment_changes change
                     WHERE change.provider_tenant_id = commitment.provider_tenant_id
                       AND change.resource_key = commitment.resource_key
                       AND change.change_kind = 'TEMPORARY_OVERRIDE'
                       AND change.lifecycle_state = 'PUBLISHED'
                       AND change.override_expires_at > CURRENT_TIMESTAMP
                     ORDER BY change.published_at DESC, change.change_request_id DESC
                     LIMIT 1
              ) active_override ON TRUE
            """;

    private static final String RESOURCE_CHANGE_SELECT = """
            SELECT change.change_request_id, change.provider_tenant_id,
                   tenant.tenant_key, tenant.display_name AS tenant_display_name,
                   change.resource_key, change.change_kind,
                   change.baseline_commitment_version, change.baseline_definition,
                   change.proposed_definition, change.commercial_renewal_revision_id,
                   change.override_expires_at,
                   CASE
                       WHEN change.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
                            AND change.decision_due_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                       WHEN change.lifecycle_state = 'PUBLISHED'
                            AND change.change_kind = 'TEMPORARY_OVERRIDE'
                            AND change.override_expires_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                       ELSE change.lifecycle_state
                   END AS lifecycle_state,
                   change.reservation_state, change.justification,
                   change.decision_due_at, change.requested_by, change.requested_at,
                   change.decided_by, change.decided_at, change.decision_reason,
                   change.published_by, change.published_at, change.version, change.updated_at
              FROM prv_resource_commitment_changes change
              JOIN prv_tenants tenant
                ON tenant.provider_tenant_id = change.provider_tenant_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ResourceCommitmentJdbcRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean tenantExists(UUID tenantId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM prv_tenants WHERE provider_tenant_id = :tenantId)
                """, new MapSqlParameterSource("tenantId", tenantId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public List<CommitmentRow> commitments(UUID tenantId, int fetchLimit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("fetchLimit", fetchLimit);
        String where = "";
        if (tenantId != null) {
            where = " WHERE commitment.provider_tenant_id = :tenantId";
            parameters.addValue("tenantId", tenantId);
        }
        return jdbc.query(COMMITMENT_SELECT + where
                + " ORDER BY tenant.tenant_key, commitment.resource_key LIMIT :fetchLimit",
                parameters, this::commitmentRow);
    }

    public Optional<CommitmentRow> commitment(UUID tenantId, String resourceKey) {
        return jdbc.query(COMMITMENT_SELECT + """
                 WHERE commitment.provider_tenant_id = :tenantId
                   AND commitment.resource_key = :resourceKey
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("resourceKey", resourceKey), this::commitmentRow).stream().findFirst();
    }

    public Optional<CommitmentRow> lockCommitment(UUID tenantId, String resourceKey) {
        return jdbc.query(COMMITMENT_SELECT + """
                 WHERE commitment.provider_tenant_id = :tenantId
                   AND commitment.resource_key = :resourceKey
                 FOR UPDATE OF commitment
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("resourceKey", resourceKey), this::commitmentRow).stream().findFirst();
    }

    public CommitmentRow createCommitment(
            UUID tenantId,
            String resourceKey,
            UpsertCommitmentRequest request,
            Long actorId) {
        jdbc.update("""
                INSERT INTO prv_tenant_resource_commitments (
                    provider_tenant_id, resource_key, unit, quota_limit, budget_limit,
                    currency_code, control_period_start, control_period_end, control_mode,
                    lifecycle_state, updated_by)
                VALUES (
                    :tenantId, :resourceKey, :unit, :quotaLimit, :budgetLimit,
                    :currencyCode, :controlPeriodStart, :controlPeriodEnd, :controlMode,
                    :lifecycleState, :actorId)
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("resourceKey", resourceKey)
                .addValue("unit", request.unit())
                .addValue("quotaLimit", request.quotaLimit())
                .addValue("budgetLimit", request.budgetLimit())
                .addValue("currencyCode", request.currencyCode())
                .addValue("controlPeriodStart", request.controlPeriodStartsAt())
                .addValue("controlPeriodEnd", request.controlPeriodEndsAt())
                .addValue("controlMode", request.controlMode())
                .addValue("lifecycleState", request.lifecycleState())
                .addValue("actorId", actorId));
        return commitment(tenantId, resourceKey).orElseThrow();
    }

    public boolean updateCommitment(
            UUID tenantId,
            String resourceKey,
            long version,
            UpsertCommitmentRequest request,
            Long actorId) {
        return jdbc.update("""
                UPDATE prv_tenant_resource_commitments
                   SET unit = :unit, quota_limit = :quotaLimit, budget_limit = :budgetLimit,
                       currency_code = :currencyCode, control_period_start = :controlPeriodStart,
                       control_period_end = :controlPeriodEnd, control_mode = :controlMode,
                       lifecycle_state = :lifecycleState,
                       updated_by = :actorId, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE provider_tenant_id = :tenantId
                   AND resource_key = :resourceKey
                   AND version = :version
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("resourceKey", resourceKey)
                .addValue("version", version)
                .addValue("unit", request.unit())
                .addValue("quotaLimit", request.quotaLimit())
                .addValue("budgetLimit", request.budgetLimit())
                .addValue("currencyCode", request.currencyCode())
                .addValue("controlPeriodStart", request.controlPeriodStartsAt())
                .addValue("controlPeriodEnd", request.controlPeriodEndsAt())
                .addValue("controlMode", request.controlMode())
                .addValue("lifecycleState", request.lifecycleState())
                .addValue("actorId", actorId)) == 1;
    }

    public boolean publishedCommercialEvidenceMatchesTenant(UUID tenantId, UUID revisionId) {
        Boolean matches = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1
                      FROM prv_subscription_renewal_revisions revision
                      JOIN prv_organization_subscriptions subscription
                        ON subscription.organization_subscription_id = revision.organization_subscription_id
                      JOIN prv_tenants tenant
                        ON tenant.organization_id = subscription.organization_id
                     WHERE tenant.provider_tenant_id = :tenantId
                       AND revision.renewal_revision_id = :revisionId
                       AND revision.lifecycle_state = 'PUBLISHED')
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("revisionId", revisionId), Boolean.class);
        return Boolean.TRUE.equals(matches);
    }

    public boolean hasActiveTemporaryOverride(UUID tenantId, String resourceKey) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1
                      FROM prv_resource_commitment_changes
                     WHERE provider_tenant_id = :tenantId
                       AND resource_key = :resourceKey
                       AND change_kind = 'TEMPORARY_OVERRIDE'
                       AND lifecycle_state = 'PUBLISHED'
                       AND override_expires_at > CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("resourceKey", resourceKey), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public List<ResourceCommitmentChangeRow> resourceChanges(UUID tenantId, int fetchLimit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("fetchLimit", fetchLimit);
        String where = "";
        if (tenantId != null) {
            where = " WHERE change.provider_tenant_id = :tenantId";
            parameters.addValue("tenantId", tenantId);
        }
        return jdbc.query(RESOURCE_CHANGE_SELECT + where
                + " ORDER BY change.updated_at DESC, change.created_at DESC,"
                + " change.change_request_id DESC"
                + " LIMIT :fetchLimit",
                parameters, this::resourceCommitmentChangeRow);
    }

    public Optional<ResourceCommitmentChangeRow> resourceChange(UUID changeRequestId) {
        return jdbc.query(RESOURCE_CHANGE_SELECT
                        + " WHERE change.change_request_id = :changeRequestId",
                new MapSqlParameterSource("changeRequestId", changeRequestId),
                this::resourceCommitmentChangeRow).stream().findFirst();
    }

    public Optional<ResourceCommitmentChangeRow> lockResourceChange(UUID changeRequestId) {
        return jdbc.query(RESOURCE_CHANGE_SELECT
                        + " WHERE change.change_request_id = :changeRequestId FOR UPDATE OF change",
                new MapSqlParameterSource("changeRequestId", changeRequestId),
                this::resourceCommitmentChangeRow).stream().findFirst();
    }

    public Optional<ResourceCommitmentChangeRow> resourceChangeByRequestKey(
            Long requesterId,
            String requestKey) {
        return jdbc.query(RESOURCE_CHANGE_SELECT + """
                 WHERE change.requested_by = :requesterId
                   AND change.request_key = :requestKey
                """, new MapSqlParameterSource("requesterId", requesterId)
                .addValue("requestKey", requestKey), this::resourceCommitmentChangeRow)
                .stream().findFirst();
    }

    public ResourceCommitmentChangeRow createResourceChange(
            UUID changeRequestId,
            UUID tenantId,
            String resourceKey,
            CreateResourceCommitmentChangeRequest request,
            JsonNode baselineDefinition,
            JsonNode proposedDefinition,
            Instant decisionDueAt,
            Long actorId) {
        expireResourceChanges();
        jdbc.update("""
                INSERT INTO prv_resource_commitment_changes (
                    change_request_id, provider_tenant_id, resource_key, change_kind,
                    baseline_commitment_version, baseline_definition, proposed_definition,
                    commercial_renewal_revision_id, override_expires_at, justification,
                    request_key, decision_due_at, requested_by)
                VALUES (
                    :changeRequestId, :tenantId, :resourceKey, :changeKind,
                    :baselineVersion, CAST(:baselineDefinition AS JSONB),
                    CAST(:proposedDefinition AS JSONB), :commercialRevisionId,
                    :overrideExpiresAt, :justification, :requestKey, :decisionDueAt, :actorId)
                """, new MapSqlParameterSource("changeRequestId", changeRequestId)
                .addValue("tenantId", tenantId)
                .addValue("resourceKey", resourceKey)
                .addValue("changeKind", request.changeKind())
                .addValue("baselineVersion", request.baselineCommitmentVersion())
                .addValue("baselineDefinition",
                        baselineDefinition == null ? null : json(baselineDefinition))
                .addValue("proposedDefinition", json(proposedDefinition))
                .addValue("commercialRevisionId", request.commercialRenewalRevisionId())
                .addValue("overrideExpiresAt", request.overrideExpiresAt())
                .addValue("justification", request.justification())
                .addValue("requestKey", request.requestKey())
                .addValue("decisionDueAt", decisionDueAt)
                .addValue("actorId", actorId));
        return resourceChange(changeRequestId).orElseThrow();
    }

    public boolean decideResourceChange(
            UUID changeRequestId,
            ResourceCommitmentChangeDecisionRequest request,
            Long actorId) {
        String nextState = "APPROVED".equals(request.decision()) ? "APPROVED" : "REJECTED";
        return jdbc.update("""
                UPDATE prv_resource_commitment_changes
                   SET lifecycle_state = :nextState, decided_by = :actorId,
                       decided_at = CURRENT_TIMESTAMP, decision_reason = :reason,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE change_request_id = :changeRequestId
                   AND lifecycle_state = 'PENDING_APPROVAL'
                   AND decision_due_at > CURRENT_TIMESTAMP
                   AND requested_by <> :actorId
                   AND version = :version
                """, new MapSqlParameterSource("changeRequestId", changeRequestId)
                .addValue("nextState", nextState)
                .addValue("actorId", actorId)
                .addValue("reason", request.reason())
                .addValue("version", request.version())) == 1;
    }

    public boolean markResourceChangePublished(
            UUID changeRequestId,
            long version,
            Long actorId) {
        return jdbc.update("""
                UPDATE prv_resource_commitment_changes
                   SET lifecycle_state = 'PUBLISHED', published_by = :actorId,
                       published_at = CURRENT_TIMESTAMP, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE change_request_id = :changeRequestId
                   AND lifecycle_state = 'APPROVED'
                   AND decision_due_at > CURRENT_TIMESTAMP
                   AND (change_kind <> 'TEMPORARY_OVERRIDE'
                        OR override_expires_at > CURRENT_TIMESTAMP)
                   AND version = :version
                """, new MapSqlParameterSource("changeRequestId", changeRequestId)
                .addValue("actorId", actorId)
                .addValue("version", version)) == 1;
    }

    private void expireResourceChanges() {
        jdbc.update("""
                UPDATE prv_resource_commitment_changes
                   SET lifecycle_state = 'EXPIRED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE (lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
                            AND decision_due_at <= CURRENT_TIMESTAMP)
                    OR (lifecycle_state = 'PUBLISHED'
                            AND change_kind = 'TEMPORARY_OVERRIDE'
                            AND override_expires_at <= CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource());
    }

    public boolean hasLedgerEntries(UUID tenantId, String resourceKey) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1
                      FROM prv_tenant_resource_ledger
                     WHERE provider_tenant_id = :tenantId
                       AND resource_key = :resourceKey)
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("resourceKey", resourceKey), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public LedgerTotalsRow ledgerTotals(
            UUID tenantId,
            String resourceKey,
            Instant controlPeriodStart,
            Instant controlPeriodEnd) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE entry_type = 'ALLOCATE'), 0) AS allocated,
                       COALESCE(SUM(amount) FILTER (WHERE entry_type = 'RELEASE'), 0) AS released,
                       COALESCE(SUM(amount) FILTER (WHERE entry_type = 'ADJUST'), 0) AS adjusted,
                       COALESCE(SUM(amount) FILTER (WHERE entry_type = 'METER'), 0) AS metered,
                       COALESCE(SUM(amount) FILTER (WHERE entry_type = 'BUDGET_RESERVE'), 0) AS budget_reserved,
                       COALESCE(SUM(amount) FILTER (WHERE entry_type = 'BUDGET_RELEASE'), 0) AS budget_released,
                       COALESCE(SUM(amount) FILTER (WHERE entry_type = 'BUDGET_SPEND'), 0) AS budget_spent
                 FROM prv_tenant_resource_ledger
                 WHERE provider_tenant_id = :tenantId
                   AND resource_key = :resourceKey
                   AND ((:controlPeriodStart IS NULL AND control_period_start IS NULL)
                     OR (control_period_start = :controlPeriodStart
                         AND control_period_end = :controlPeriodEnd))
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("resourceKey", resourceKey)
                .addValue("controlPeriodStart", controlPeriodStart)
                .addValue("controlPeriodEnd", controlPeriodEnd), (result, ignored) -> new LedgerTotalsRow(
                        decimal(result, "allocated"), decimal(result, "released"),
                        decimal(result, "adjusted"), decimal(result, "metered"),
                        decimal(result, "budget_reserved"), decimal(result, "budget_released"),
                        decimal(result, "budget_spent")));
    }

    public InternalEvidenceFreshnessRow internalEvidenceFreshness(
            UUID tenantId,
            String resourceKey,
            Instant controlPeriodStart,
            Instant controlPeriodEnd) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS entry_count,
                       MAX(occurred_at) AS latest_occurred_at,
                       MAX(recorded_at) AS latest_recorded_at
                  FROM prv_tenant_resource_ledger
                 WHERE provider_tenant_id = :tenantId
                   AND resource_key = :resourceKey
                   AND ((:controlPeriodStart IS NULL AND control_period_start IS NULL)
                     OR (control_period_start = :controlPeriodStart
                         AND control_period_end = :controlPeriodEnd))
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("resourceKey", resourceKey)
                .addValue("controlPeriodStart", controlPeriodStart)
                .addValue("controlPeriodEnd", controlPeriodEnd), (result, ignored) ->
                        new InternalEvidenceFreshnessRow(
                                result.getLong("entry_count"),
                                result.getObject("latest_occurred_at", Instant.class),
                                result.getObject("latest_recorded_at", Instant.class)));
    }

    public List<LedgerRow> ledger(UUID tenantId, String resourceKey, int limit) {
        return jdbc.query("""
                SELECT ledger_entry_id, provider_tenant_id, resource_key, entry_type, amount,
                       unit, currency_code, evidence_ref, idempotency_key, reason, occurred_at,
                       control_period_start, control_period_end, recorded_by, recorded_at
                  FROM prv_tenant_resource_ledger
                 WHERE provider_tenant_id = :tenantId
                   AND resource_key = :resourceKey
                 ORDER BY occurred_at DESC, recorded_at DESC, ledger_entry_id DESC
                 LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("resourceKey", resourceKey)
                .addValue("limit", limit), this::ledgerRow);
    }

    public Optional<LedgerRow> ledgerByIdempotency(UUID tenantId, String idempotencyKey) {
        return jdbc.query("""
                SELECT ledger_entry_id, provider_tenant_id, resource_key, entry_type, amount,
                       unit, currency_code, evidence_ref, idempotency_key, reason, occurred_at,
                       control_period_start, control_period_end, recorded_by, recorded_at
                  FROM prv_tenant_resource_ledger
                 WHERE provider_tenant_id = :tenantId
                   AND idempotency_key = :idempotencyKey
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("idempotencyKey", idempotencyKey), this::ledgerRow).stream().findFirst();
    }

    public boolean appendLedger(
            UUID entryId,
            UUID tenantId,
            String resourceKey,
            CommitmentRow commitment,
            AppendLedgerEntryRequest request,
            Long actorId) {
        return jdbc.update("""
                INSERT INTO prv_tenant_resource_ledger (
                    ledger_entry_id, provider_tenant_id, resource_key, entry_type, amount,
                    unit, currency_code, evidence_ref, idempotency_key, reason, occurred_at,
                    control_period_start, control_period_end, recorded_by)
                VALUES (
                    :entryId, :tenantId, :resourceKey, :entryType, :amount,
                    :unit, :currencyCode, :evidenceRef, :idempotencyKey, :reason, :occurredAt,
                    :controlPeriodStart, :controlPeriodEnd, :actorId)
                ON CONFLICT (provider_tenant_id, idempotency_key) DO NOTHING
                """, new MapSqlParameterSource("entryId", entryId)
                .addValue("tenantId", tenantId)
                .addValue("resourceKey", resourceKey)
                .addValue("entryType", request.entryType())
                .addValue("amount", request.amount())
                .addValue("unit", request.unit())
                .addValue("currencyCode", request.currencyCode())
                .addValue("evidenceRef", request.evidenceRef())
                .addValue("idempotencyKey", request.idempotencyKey())
                .addValue("reason", request.reason())
                .addValue("occurredAt", request.occurredAt())
                .addValue("controlPeriodStart", commitment.controlPeriodStart())
                .addValue("controlPeriodEnd", commitment.controlPeriodEnd())
                .addValue("actorId", actorId)) == 1;
    }
    private CommitmentRow commitmentRow(ResultSet result, int ignored) throws SQLException {
        return new CommitmentRow(
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("tenant_key"),
                result.getString("tenant_display_name"),
                result.getString("resource_key"),
                result.getString("unit"),
                nullableDecimal(result, "quota_limit"),
                nullableDecimal(result, "budget_limit"),
                result.getString("currency_code"),
                result.getString("lifecycle_state"),
                result.getString("source_system"),
                result.getString("external_feed_state"),
                result.getString("control_mode"),
                result.getString("control_scope"),
                result.getObject("control_period_start", Instant.class),
                result.getObject("control_period_end", Instant.class),
                result.getObject("active_override_id", UUID.class),
                result.getObject("active_override_expires_at", Instant.class),
                result.getLong("version"),
                result.getObject("updated_at", Instant.class));
    }

    private ResourceCommitmentChangeRow resourceCommitmentChangeRow(
            ResultSet result,
            int ignored) throws SQLException {
        return new ResourceCommitmentChangeRow(
                result.getObject("change_request_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("tenant_key"),
                result.getString("tenant_display_name"),
                result.getString("resource_key"),
                result.getString("change_kind"),
                result.getObject("baseline_commitment_version", Long.class),
                nullableNode(result.getString("baseline_definition")),
                node(result.getString("proposed_definition")),
                result.getObject("commercial_renewal_revision_id", UUID.class),
                result.getObject("override_expires_at", Instant.class),
                result.getString("lifecycle_state"),
                result.getString("reservation_state"),
                result.getString("justification"),
                result.getObject("decision_due_at", Instant.class),
                result.getLong("requested_by"),
                result.getObject("requested_at", Instant.class),
                result.getObject("decided_by", Long.class),
                result.getObject("decided_at", Instant.class),
                result.getString("decision_reason"),
                result.getObject("published_by", Long.class),
                result.getObject("published_at", Instant.class),
                result.getLong("version"),
                result.getObject("updated_at", Instant.class));
    }

    private LedgerRow ledgerRow(ResultSet result, int ignored) throws SQLException {
        return new LedgerRow(
                result.getObject("ledger_entry_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("resource_key"),
                result.getString("entry_type"),
                decimal(result, "amount"),
                result.getString("unit"),
                result.getString("currency_code"),
                result.getString("evidence_ref"),
                result.getString("idempotency_key"),
                result.getString("reason"),
                result.getObject("occurred_at", Instant.class),
                result.getObject("control_period_start", Instant.class),
                result.getObject("control_period_end", Instant.class),
                result.getObject("recorded_by", Long.class),
                result.getObject("recorded_at", Instant.class));
    }

    private BigDecimal decimal(ResultSet result, String column) throws SQLException {
        BigDecimal value = result.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal nullableDecimal(ResultSet result, String column) throws SQLException {
        return result.getBigDecimal(column);
    }

    private JsonNode node(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider resource-governance JSON is invalid.", exception);
        }
    }

    private JsonNode nullableNode(String value) {
        return value == null ? null : node(value);
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider resource-governance JSON cannot be serialized.", exception);
        }
    }
}
