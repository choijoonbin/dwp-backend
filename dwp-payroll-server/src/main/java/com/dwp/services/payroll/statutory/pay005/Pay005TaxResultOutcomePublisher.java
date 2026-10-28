package com.dwp.services.payroll.statutory.pay005;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Controlled publisher seam for the typed PAY-005 TAX_RESULT fact.
 *
 * <p>This deliberately is not a Spring bean. A separately admitted country
 * rule process must construct it with the restricted projection-publisher
 * login; the Payroll runtime cannot publish statutory facts.</p>
 */
final class Pay005TaxResultOutcomePublisher {

    private final NamedParameterJdbcTemplate jdbc;
    private final Pay005Canonical canonical;
    private final TransactionTemplate transactions;

    Pay005TaxResultOutcomePublisher(
            NamedParameterJdbcTemplate jdbc, Pay005Canonical canonical) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.canonical = Objects.requireNonNull(canonical, "canonical");
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(
                        jdbc.getJdbcTemplate().getDataSource(), "dataSource")));
    }

    Pay005Models.PreparedTaxResultFact prepare(
            Pay005Models.TaxResultProviderOutcome outcome) {
        ArrayList<Pay005Models.SourceLine> lines =
                new ArrayList<>(outcome.sourceLines());
        lines.sort(Comparator.comparing(line -> line.resultLineId().toString()));
        if (lines.stream().map(Pay005Models.SourceLine::resultLineId)
                .distinct().count() != lines.size()) {
            throw invalid("Source result-line IDs must be unique and canonical.");
        }
        BigDecimal sum = lines.stream()
                .map(line -> line.contribution().amount())
                .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add);
        if (lines.stream().anyMatch(line ->
                !line.contribution().currency().equals(
                        outcome.amount().currency()))
                || sum.compareTo(outcome.amount().amount()) != 0) {
            throw invalid("Typed tax contributions do not reconcile.");
        }

        List<Map<String, Object>> sourceBinding = lines.stream().map(line -> {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("resultLineId", line.resultLineId());
            value.put("amount", line.contribution().amount().toPlainString());
            value.put("currency", line.contribution().currency());
            value.put("sourceSemantic", line.sourceSemantic());
            return Map.copyOf(value);
        }).toList();
        String sourceDigest = canonical.digest(Map.of(
                "schema", "Pay005TaxResultSourceLineSet.v1",
                "sourceLines", sourceBinding));

        LinkedHashMap<String, Object> providerBinding = new LinkedHashMap<>();
        providerBinding.put("schema", "Pay005TypedTaxResultOutcome.v1");
        providerBinding.put("tenantId", outcome.tenantId());
        providerBinding.put("runId", outcome.runId());
        providerBinding.put("attemptNo", outcome.attemptNo());
        providerBinding.put("workerResultId", outcome.workerResultId());
        providerBinding.put("packId", outcome.packId());
        providerBinding.put("activationRevision", outcome.activationRevision());
        providerBinding.put("policyVersion", outcome.policyVersion());
        providerBinding.put("amount", outcome.amount().amount().toPlainString());
        providerBinding.put("currency", outcome.amount().currency());
        providerBinding.put("sourceLineSetDigest", sourceDigest);
        providerBinding.put("certifiedProviderEvidenceDigest",
                outcome.certifiedProviderEvidenceDigest());
        String providerDigest = canonical.digest(providerBinding);
        String resultDigest = canonical.digest(Map.of(
                "schema", "Pay005TaxResultFact.v1",
                "providerOutcomeDigest", providerDigest,
                "recordedAt", outcome.recordedAt()));
        UUID factId = UUID.nameUUIDFromBytes(
                ("pay005-tax-result:" + outcome.tenantId() + ":"
                        + outcome.workerResultId() + ":" + resultDigest)
                        .getBytes(StandardCharsets.UTF_8));
        Pay005Models.TaxResultProviderOutcome canonicalOutcome =
                new Pay005Models.TaxResultProviderOutcome(
                        outcome.tenantId(), outcome.runId(), outcome.attemptNo(),
                        outcome.workerResultId(), outcome.packId(),
                        outcome.activationRevision(), outcome.policyVersion(),
                        outcome.amount(), lines,
                        outcome.certifiedProviderEvidenceDigest(),
                        outcome.recordedAt());
        return new Pay005Models.PreparedTaxResultFact(
                factId, canonicalOutcome,
                lines.stream().map(Pay005Models.SourceLine::resultLineId)
                        .toList(),
                sourceDigest, providerDigest, resultDigest);
    }

    void publish(Pay005Models.TaxResultProviderOutcome outcome) {
        Pay005Models.PreparedTaxResultFact prepared = prepare(outcome);
        transactions.executeWithoutResult(ignored -> insertOrReplay(prepared));
    }

    private void insertOrReplay(Pay005Models.PreparedTaxResultFact prepared) {
        Pay005Models.TaxResultProviderOutcome outcome = prepared.outcome();
        bindTenant(outcome.tenantId());
        List<Map<String, Object>> existing = existingFact(prepared);
        if (!existing.isEmpty()) {
            verifyExactReplay(prepared, existing);
            return;
        }
        int inserted = jdbc.update("""
                INSERT INTO pay_tax_result_facts (
                    tenant_id, tax_result_fact_id, run_id, attempt_no,
                    worker_result_id, pack_id, activation_revision,
                    policy_version, provider_result_type,
                    provider_source_semantics, provider_outcome_digest,
                    amount, currency, source_result_line_ids,
                    source_line_set_digest, result_digest, recorded_at)
                VALUES (
                    :tenantId, :factId, :runId, :attemptNo,
                    :workerResultId, :packId, :activationRevision,
                    :policyVersion, 'TAX_RESULT',
                    'TYPED_COUNTRY_RULE_OUTPUT', :providerOutcomeDigest,
                    :amount, :currency, :sourceLineIds,
                    :sourceLineSetDigest, :resultDigest, :recordedAt)
                ON CONFLICT (tenant_id, worker_result_id) DO NOTHING
                """, parameters(prepared));
        if (inserted == 1) return;

        verifyExactReplay(prepared, existingFact(prepared));
    }

    private List<Map<String, Object>> existingFact(
            Pay005Models.PreparedTaxResultFact prepared) {
        return jdbc.queryForList("""
                SELECT tax_result_fact_id, provider_outcome_digest,
                       source_line_set_digest, result_digest
                  FROM pay_tax_result_facts
                 WHERE tenant_id = :tenantId
                   AND worker_result_id = :workerResultId
                """, parameters(prepared));
    }

    private void verifyExactReplay(
            Pay005Models.PreparedTaxResultFact prepared,
            List<Map<String, Object>> existing) {
        boolean exactReplay = existing.size() == 1
                && prepared.taxResultFactId().equals(
                existing.getFirst().get("tax_result_fact_id"))
                && prepared.providerOutcomeDigest().equals(
                existing.getFirst().get("provider_outcome_digest"))
                && prepared.sourceLineSetDigest().equals(
                existing.getFirst().get("source_line_set_digest"))
                && prepared.resultDigest().equals(
                existing.getFirst().get("result_digest"));
        if (!exactReplay) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "TAX_RESULT identity was reused with another digest.");
        }
    }

    private MapSqlParameterSource parameters(
            Pay005Models.PreparedTaxResultFact prepared) {
        Pay005Models.TaxResultProviderOutcome outcome = prepared.outcome();
        return new MapSqlParameterSource()
                .addValue("tenantId", outcome.tenantId())
                .addValue("factId", prepared.taxResultFactId())
                .addValue("runId", outcome.runId())
                .addValue("attemptNo", outcome.attemptNo())
                .addValue("workerResultId", outcome.workerResultId())
                .addValue("packId", outcome.packId())
                .addValue("activationRevision", outcome.activationRevision())
                .addValue("policyVersion", outcome.policyVersion())
                .addValue("providerOutcomeDigest",
                        prepared.providerOutcomeDigest())
                .addValue("amount", outcome.amount().amount())
                .addValue("currency", outcome.amount().currency())
                .addValue("sourceLineIds",
                        prepared.sourceResultLineIds().toArray(UUID[]::new))
                .addValue("sourceLineSetDigest",
                        prepared.sourceLineSetDigest())
                .addValue("resultDigest", prepared.resultDigest())
                .addValue("recordedAt", Timestamp.from(outcome.recordedAt()));
    }

    private void bindTenant(long tenantId) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource(
                        "tenantId", Long.toString(tenantId)), String.class);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
