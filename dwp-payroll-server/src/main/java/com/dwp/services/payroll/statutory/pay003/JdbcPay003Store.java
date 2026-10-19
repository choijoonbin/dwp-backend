package com.dwp.services.payroll.statutory.pay003;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_STATUTORY_INSURANCE.enabled",
        havingValue = "true",
        matchIfMissing = false)
class JdbcPay003Store implements Pay003Store {

    private static final String PACK_SELECT = """
            SELECT snapshot.tenant_id, snapshot.pack_id, snapshot.pack_key,
                   snapshot.pack_version, snapshot.snapshot_revision,
                   snapshot.legal_entity_id, snapshot.payroll_group_id,
                   snapshot.jurisdiction_code, snapshot.payload_digest,
                   snapshot.artifact_digest, snapshot.signature_digest,
                   snapshot.signature_algorithm, snapshot.signing_key_id,
                   snapshot.compatibility_version,
                   snapshot.policy_schema_version,
                   snapshot.official_reference_set,
                   snapshot.golden_pack_digest,
                   snapshot.statutory_approval_receipt_id,
                   snapshot.installer_subject_public_id,
                   snapshot.statutory_approver_subject_public_id,
                   snapshot.effective_from,
                   COALESCE(head.effective_to, snapshot.effective_to) effective_to,
                   COALESCE(head.activation_revision,
                            snapshot.snapshot_revision) current_revision,
                   COALESCE(head.lifecycle_state,
                            snapshot.lifecycle_state) lifecycle_state
              FROM pay_country_pack_version_snapshots snapshot
              LEFT JOIN LATERAL (
                    SELECT history.activation_revision,
                           history.lifecycle_state, history.effective_to
                      FROM pay_country_pack_activation_versions history
                     WHERE history.tenant_id = snapshot.tenant_id
                       AND history.pack_id = snapshot.pack_id
                       AND history.occurred_at <= COALESCE(
                           CAST(:asOf AS TIMESTAMPTZ),
                           'infinity'::TIMESTAMPTZ)
                     ORDER BY history.activation_revision DESC
                     LIMIT 1
              ) head ON TRUE
             WHERE snapshot.tenant_id = :tenantId
               AND snapshot.legal_entity_id = :legalEntityId
               AND snapshot.payroll_group_id = :payrollGroupId
               AND snapshot.recorded_at <= COALESCE(
                   CAST(:asOf AS TIMESTAMPTZ), 'infinity'::TIMESTAMPTZ)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Pay003Canonical canonical;

    JdbcPay003Store(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            Pay003Canonical canonical) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.canonical = canonical;
    }

    @Override
    public void bindTenant(long tenantId) {
        if (tenantId < 1) {
            throw new BaseException(ErrorCode.TENANT_MISSING,
                    "A positive Payroll tenant is required.");
        }
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource(
                        "tenantId", Long.toString(tenantId)), String.class);
    }

    @Override
    public void lockIdempotency(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String idempotencyKey) {
        bindTenant(tenantId);
        String lockKey = tenantId + ":PAY_COUNTRY_PACK_ACTIVATE:"
                + subjectPrincipalPublicId + ":" + idempotencyKey;
        jdbc.queryForObject("""
                SELECT 1 FROM (SELECT pg_advisory_xact_lock(
                    hashtextextended(:lockKey, 0))) locked
                """, new MapSqlParameterSource("lockKey", lockKey), Integer.class);
    }

    @Override
    public Optional<Pay003Models.StoredReceipt> receipt(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String idempotencyKey) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT receipt_id, subject_principal_public_id,
                       population_scope_digest, field_policy_revision,
                       purpose_code, authorization_revision, receipt_status,
                       correlation_id, request_digest, created_at,
                       activation_revision, result_ref
                  FROM pay_country_pack_command_receipts
                 WHERE tenant_id = :tenantId
                   AND subject_principal_public_id = :subjectId
                   AND originating_action = 'PAY_COUNTRY_PACK_ACTIVATE'
                   AND idempotency_key = :idempotencyKey
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("subjectId", subjectPrincipalPublicId)
                .addValue("idempotencyKey", idempotencyKey),
                (result, row) -> {
                    String digest = result.getString("request_digest");
                    return new Pay003Models.StoredReceipt(
                            digest, new Pay003Models.PayrollCommandReceipt(
                            result.getObject("receipt_id", UUID.class),
                            "COUNTRY_PACK_ACTIVATE",
                            Pay003Models.ACTIVATE_ACTION,
                            result.getObject(
                                    "subject_principal_public_id", UUID.class),
                            result.getString("population_scope_digest"),
                            result.getLong("field_policy_revision"),
                            result.getString("purpose_code"),
                            result.getLong("authorization_revision"),
                            result.getString("receipt_status"),
                            result.getObject("correlation_id", UUID.class),
                            digest,
                            result.getObject(
                                    "created_at", OffsetDateTime.class).toInstant(),
                            result.getLong("activation_revision"),
                            result.getString("result_ref")));
                }).stream().findFirst();
    }

    @Override
    public Optional<Pay003Models.PackSnapshot> pack(
            Pay003Authority.Actor actor, UUID packId, boolean lock) {
        bindTenant(actor.subject().tenantId());
        if (lock) {
            jdbc.queryForObject("""
                    SELECT 1 FROM (SELECT pg_advisory_xact_lock(
                        hashtextextended(:lockKey, 0))) locked
                    """, new MapSqlParameterSource(
                    "lockKey", actor.subject().tenantId()
                            + ":PAY003:PACK:" + packId), Integer.class);
        }
        // The immutable source stays SELECT-only for runtime. The pack advisory
        // lock serializes commands; the insert guard performs lifecycle CAS
        // under its scope lock without granting runtime UPDATE privileges.
        return jdbc.query(PACK_SELECT + " AND snapshot.pack_id = :packId",
                scope(actor, null).addValue("packId", packId),
                this::pack).stream().findFirst();
    }

    @Override
    public Pay003Models.SourcePage<Pay003Models.PackSnapshot> statutory(
            Pay003Authority.Actor actor,
            Pay003Models.QueryRequest query,
            Instant asOf) {
        try {
            bindTenant(actor.subject().tenantId());
            List<Pay003Models.PackSnapshot> rows = jdbc.query(
                    PACK_SELECT, scope(actor, asOf), this::pack).stream()
                    .filter(snapshot -> matchesPack(snapshot, query))
                    .toList();
            return new Pay003Models.SourcePage<>(
                    Pay003Models.Availability.AVAILABLE, rows);
        } catch (RuntimeException exception) {
            return Pay003Models.SourcePage.unavailable();
        }
    }

    @Override
    public Pay003Models.SourcePage<Pay003Models.SocialInsuranceFact> socialInsurance(
            Pay003Authority.Actor actor,
            Pay003Models.QueryRequest query,
            Instant asOf) {
        if (query.filter().keySet().stream()
                .anyMatch(key -> key.startsWith("filter[taxResult]"))) {
            return Pay003Models.SourcePage.unavailable();
        }
        try {
            bindTenant(actor.subject().tenantId());
            List<Pay003Models.SocialInsuranceFact> rows = jdbc.query("""
                    SELECT fact.tenant_id, fact.worker_result_id,
                           fact.social_insurance_fact_id,
                           worker.worker_id, fact.amount, fact.currency,
                           fact.result_digest, fact.policy_version,
                           snapshot.pack_version, fact.activation_revision,
                           activation.effective_from, activation.effective_to
                      FROM pay_social_insurance_result_facts fact
                      JOIN pay_worker_results worker
                        ON worker.tenant_id = fact.tenant_id
                       AND worker.run_id = fact.run_id
                       AND worker.attempt_no = fact.attempt_no
                       AND worker.worker_result_id = fact.worker_result_id
                      JOIN pay_country_pack_version_snapshots snapshot
                        ON snapshot.tenant_id = fact.tenant_id
                       AND snapshot.pack_id = fact.pack_id
                      JOIN pay_country_pack_activation_versions activation
                        ON activation.tenant_id = fact.tenant_id
                       AND activation.pack_id = fact.pack_id
                       AND activation.activation_revision = fact.activation_revision
                     WHERE fact.tenant_id = :tenantId
                       AND snapshot.legal_entity_id = :legalEntityId
                       AND snapshot.payroll_group_id = :payrollGroupId
                       AND fact.recorded_at <= :asOf
                       AND snapshot.recorded_at <= :asOf
                       AND activation.occurred_at <= :asOf
                    """, scope(actor, asOf), (result, row) ->
                    new Pay003Models.SocialInsuranceFact(
                            result.getLong("tenant_id"),
                            result.getObject("worker_result_id", UUID.class),
                            result.getObject(
                                    "social_insurance_fact_id", UUID.class),
                            "Worker " + result.getObject(
                                    "worker_id", UUID.class),
                            new Pay003Models.PostedMoney(
                                    result.getBigDecimal("amount"),
                                    result.getString("currency")),
                            result.getString("result_digest"),
                            result.getString("policy_version"),
                            result.getString("pack_version"),
                            result.getLong("activation_revision"),
                            result.getObject("effective_from", Date.class)
                                    .toLocalDate(),
                            optionalDate(result.getObject(
                                    "effective_to", Date.class)))).stream()
                    .filter(fact -> matchesFact(fact, query))
                    .toList();
            return new Pay003Models.SourcePage<>(
                    Pay003Models.Availability.AVAILABLE, rows);
        } catch (RuntimeException exception) {
            return Pay003Models.SourcePage.unavailable();
        }
    }

    @Override
    public void commit(Pay003Models.ActivationCommit commit) {
        Pay003Authority.Actor actor = commit.actor();
        Pay003Models.PackSnapshot snapshot = commit.snapshot();
        bindTenant(actor.subject().tenantId());
        try {
            jdbc.update("""
                    INSERT INTO pay_country_pack_activation_versions (
                        tenant_id, pack_id, previous_revision,
                        activation_revision, lifecycle_state,
                        effective_from, effective_to,
                        activated_by_subject_public_id, receipt_id,
                        audit_event_id, correlation_id, occurred_at)
                    VALUES (:tenantId, :packId, :previousRevision,
                        :activationRevision, 'ACTIVE', :effectiveFrom,
                        :effectiveTo, :subjectId, :receiptId,
                        :eventId, :correlationId, :occurredAt)
                    """, base(commit)
                    .addValue("previousRevision", snapshot.currentRevision())
                    .addValue("effectiveFrom", Date.valueOf(
                            snapshot.effectiveFrom()))
                    .addValue("effectiveTo", snapshot.effectiveTo() == null
                            ? null : Date.valueOf(snapshot.effectiveTo()))
                    .addValue("subjectId",
                            actor.subject().subjectPrincipalPublicId()));

            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("schema", "PayCountryPackActivationAudit.v1");
            audit.put("packId", snapshot.packId());
            audit.put("activationRevision", commit.activationRevision());
            audit.put("legalEntityId", actor.scope().legalEntityId());
            audit.put("payrollGroupId", actor.scope().payrollGroupId());
            audit.put("requestDigest", commit.requestDigest());
            jdbc.update("""
                    INSERT INTO pay_country_pack_command_receipts (
                        tenant_id, receipt_id, subject_principal_public_id,
                        originating_action, idempotency_key, request_digest,
                        pack_id, expected_revision, activation_revision,
                        legal_entity_id, payroll_group_id,
                        population_scope_digest, purpose_code,
                        field_policy_revision, authorization_revision,
                        receipt_status, correlation_id, result_ref,
                        step_up_challenge_id, step_up_nonce, step_up_issuer,
                        step_up_expires_at, step_up_payload_sha256,
                        step_up_decision_revision, audit_payload, created_at)
                    VALUES (:tenantId, :receiptId, :subjectId,
                        'PAY_COUNTRY_PACK_ACTIVATE', :idempotencyKey,
                        :requestDigest, :packId, :expectedRevision,
                        :activationRevision, :legalEntityId, :payrollGroupId,
                        :populationScopeDigest, 'STATUTORY_CONFIGURATION',
                        :fieldPolicyRevision,
                        :authorizationRevision, 'SUCCEEDED', :correlationId,
                        :resultRef, :challengeId, :nonce, :issuer,
                        :expiresAt, :payloadSha256, :decisionRevision,
                        CAST(:auditPayload AS JSONB), :occurredAt)
                    """, base(commit)
                    .addValue("subjectId",
                            actor.subject().subjectPrincipalPublicId())
                    .addValue("idempotencyKey",
                            commit.headers().idempotencyKey())
                    .addValue("requestDigest", commit.requestDigest())
                    .addValue("expectedRevision",
                            commit.headers().expectedRevision())
                    .addValue("legalEntityId", actor.scope().legalEntityId())
                    .addValue("payrollGroupId", actor.scope().payrollGroupId())
                    .addValue("populationScopeDigest",
                            actor.scope().scopeDigest())
                    .addValue("fieldPolicyRevision",
                            actor.subject().fieldPolicyRevision())
                    .addValue("authorizationRevision",
                            actor.subject().authorizationRevision())
                    .addValue("resultRef", commit.response().resultRef())
                    .addValue("challengeId",
                            commit.stepUpEvidence().challengeId())
                    .addValue("nonce", commit.stepUpEvidence().nonce())
                    .addValue("issuer", commit.stepUpEvidence().issuer())
                    .addValue("expiresAt", timestamp(
                            commit.stepUpEvidence().expiresAt()))
                    .addValue("payloadSha256",
                            commit.stepUpEvidence().payloadSha256())
                    .addValue("decisionRevision",
                            commit.stepUpEvidence().decisionRevision())
                    .addValue("auditPayload", json(audit)));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("packId", snapshot.packId());
            data.put("activationRevision", commit.activationRevision());
            data.put("lifecycleState", "ACTIVE");
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("specVersion", "1.0");
            event.put("id", commit.eventId());
            event.put("source", "dwp-payroll-server");
            event.put("type", "CountryPackActivated");
            event.put("schemaVersion", 1);
            event.put("time", commit.occurredAt());
            event.put("tenantId", actor.subject().tenantId());
            event.put("aggregateType", "COUNTRY_PACK");
            event.put("aggregateId", snapshot.packId());
            event.put("aggregateSequence", commit.activationRevision());
            event.put("correlationId", commit.headers().correlationId());
            event.put("data", data);
            jdbc.update("""
                    INSERT INTO pay_country_pack_audit_outbox_events (
                        tenant_id, event_id, receipt_id, pack_id,
                        activation_revision, event_type, schema_version,
                        aggregate_sequence, correlation_id,
                        event_payload, occurred_at)
                    VALUES (:tenantId, :eventId, :receiptId, :packId,
                        :activationRevision, 'CountryPackActivated', 1,
                        :activationRevision, :correlationId,
                        CAST(:eventPayload AS JSONB), :occurredAt)
                    """, base(commit)
                    .addValue("eventPayload", json(event)));
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "Country-pack activation lost a concurrent race.", exception);
        }
    }

    private MapSqlParameterSource base(Pay003Models.ActivationCommit commit) {
        return new MapSqlParameterSource()
                .addValue("tenantId", commit.actor().subject().tenantId())
                .addValue("packId", commit.snapshot().packId())
                .addValue("activationRevision", commit.activationRevision())
                .addValue("receiptId", commit.receiptId())
                .addValue("eventId", commit.eventId())
                .addValue("correlationId", commit.headers().correlationId())
                .addValue("occurredAt", timestamp(commit.occurredAt()));
    }

    private MapSqlParameterSource scope(Pay003Authority.Actor actor) {
        return scope(actor, null);
    }

    private MapSqlParameterSource scope(
            Pay003Authority.Actor actor, Instant asOf) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.subject().tenantId())
                .addValue("legalEntityId", actor.scope().legalEntityId())
                .addValue("payrollGroupId", actor.scope().payrollGroupId())
                .addValue("asOf", asOf == null ? null
                        : OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC));
    }

    private Pay003Models.PackSnapshot pack(
            java.sql.ResultSet result, int ignored) throws java.sql.SQLException {
        return new Pay003Models.PackSnapshot(
                result.getLong("tenant_id"),
                result.getObject("pack_id", UUID.class),
                result.getString("pack_key"), result.getString("pack_version"),
                result.getLong("snapshot_revision"),
                result.getLong("current_revision"),
                result.getObject("legal_entity_id", UUID.class),
                result.getObject("payroll_group_id", UUID.class),
                result.getString("jurisdiction_code"),
                Pay003Models.LifecycleState.valueOf(
                        result.getString("lifecycle_state")),
                result.getString("payload_digest"),
                result.getString("artifact_digest"),
                result.getString("signature_digest"),
                result.getString("signature_algorithm"),
                result.getString("signing_key_id"),
                result.getString("compatibility_version"),
                result.getString("policy_schema_version"),
                result.getString("official_reference_set"),
                result.getString("golden_pack_digest"),
                result.getObject("statutory_approval_receipt_id", UUID.class),
                result.getObject("installer_subject_public_id", UUID.class),
                result.getObject(
                        "statutory_approver_subject_public_id", UUID.class),
                result.getObject("effective_from", Date.class).toLocalDate(),
                optionalDate(result.getObject("effective_to", Date.class)));
    }

    private boolean matchesPack(
            Pay003Models.PackSnapshot row,
            Pay003Models.QueryRequest query) {
        Map<String, List<String>> filter = query.filter();
        if (!filter.getOrDefault(
                "filter[countryPackMetadata][keys]", List.of()).isEmpty()
                && !filter.get("filter[countryPackMetadata][keys]")
                .contains(row.packKey())) return false;
        if (!filter.getOrDefault(
                "filter[countryPackMetadata][versions]", List.of()).isEmpty()
                && !filter.get("filter[countryPackMetadata][versions]")
                .contains(row.packVersion())) return false;
        if (!filter.getOrDefault(
                "filter[statutoryRule][policyKeys]", List.of()).isEmpty()
                && !filter.get("filter[statutoryRule][policyKeys]")
                .contains(canonical.policyKey(row.packKey()))) return false;
        if (!filter.getOrDefault(
                "filter[activationStatus][codes]", List.of()).isEmpty()
                && !filter.get("filter[activationStatus][codes]")
                .contains(row.lifecycleState().name())) return false;
        return minimumRevision(filter, "filter[statutoryRule][minimumRevision]",
                row.currentRevision())
                && minimumRevision(filter,
                "filter[activationStatus][minimumRevision]",
                row.currentRevision())
                && effective(filter, row.effectiveFrom(), row.effectiveTo());
    }

    private boolean matchesFact(
            Pay003Models.SocialInsuranceFact row,
            Pay003Models.QueryRequest query) {
        Map<String, List<String>> filter = query.filter();
        String currency = single(filter,
                "filter[socialInsurance][currency]");
        if (currency != null && !currency.equals(row.amount().currency())) return false;
        if (!filter.getOrDefault(
                "filter[policyVersion][policyKeys]", List.of()).isEmpty()
                && !filter.get("filter[policyVersion][policyKeys]")
                .contains(canonical.policyKey(row.policyVersion()))) return false;
        String minimum = single(filter,
                "filter[socialInsurance][minimumAmount]");
        String maximum = single(filter,
                "filter[socialInsurance][maximumAmount]");
        if (minimum != null && row.amount().amount()
                .compareTo(new BigDecimal(minimum)) < 0) return false;
        if (maximum != null && row.amount().amount()
                .compareTo(new BigDecimal(maximum)) > 0) return false;
        return minimumRevision(filter,
                "filter[policyVersion][minimumRevision]",
                row.activationRevision())
                && effective(filter, row.effectiveFrom(), row.effectiveTo());
    }

    private boolean minimumRevision(
            Map<String, List<String>> filter, String key, long revision) {
        String value = single(filter, key);
        return value == null || revision >= Long.parseLong(value);
    }

    private boolean effective(
            Map<String, List<String>> filter,
            LocalDate from,
            LocalDate to) {
        String value = single(filter, "filter[effectiveOn]");
        if (value == null) return true;
        LocalDate date = LocalDate.parse(value);
        return !date.isBefore(from) && (to == null || date.isBefore(to));
    }

    private String single(Map<String, List<String>> filter, String key) {
        List<String> values = filter.get(key);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private LocalDate optionalDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("PAY-003 JSON encoding failed", exception);
        }
    }
}
