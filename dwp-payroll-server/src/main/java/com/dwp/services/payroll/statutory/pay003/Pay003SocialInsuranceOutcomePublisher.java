package com.dwp.services.payroll.statutory.pay003;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;

/**
 * Controlled publisher-side seam for a certified country-rule outcome.
 *
 * <p>This class is deliberately not a Spring bean. Production has no provider
 * binding in BASE-TFR-PAY-003; a separately admitted projection/provider
 * process must construct it with the restricted projection-publisher login.</p>
 */
final class Pay003SocialInsuranceOutcomePublisher {

    private final NamedParameterJdbcTemplate jdbc;
    private final Pay003Canonical canonical;
    private final TransactionTemplate transactions;

    Pay003SocialInsuranceOutcomePublisher(
            NamedParameterJdbcTemplate jdbc, Pay003Canonical canonical) {
        this.jdbc = jdbc;
        this.canonical = canonical;
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(
                        jdbc.getJdbcTemplate().getDataSource(), "dataSource")));
    }

    Pay003Models.PreparedSocialInsuranceFact prepare(
            Pay003Models.SocialInsuranceProviderOutcome outcome) {
        ArrayList<Pay003Models.ProviderSourceLine> lines =
                new ArrayList<>(outcome.sourceLines());
        lines.sort(Comparator.comparing(line -> line.resultLineId().toString()));
        if (lines.stream().map(Pay003Models.ProviderSourceLine::resultLineId)
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
            throw invalid("Typed provider contributions do not reconcile.");
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
                "schema", "Pay003SocialInsuranceSourceLineSet.v1",
                "sourceLines", sourceBinding));
        LinkedHashMap<String, Object> providerBinding = new LinkedHashMap<>();
        providerBinding.put("schema", "Pay003CountryRuleOutcome.v1");
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
                "schema", "Pay003SocialInsuranceResultFact.v1",
                "providerOutcomeDigest", providerDigest,
                "recordedAt", outcome.recordedAt()));
        UUID factId = UUID.nameUUIDFromBytes(
                ("pay003-social-insurance:" + outcome.tenantId() + ":"
                        + outcome.workerResultId() + ":" + resultDigest)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Pay003Models.SocialInsuranceProviderOutcome canonicalOutcome =
                new Pay003Models.SocialInsuranceProviderOutcome(
                        outcome.tenantId(), outcome.runId(), outcome.attemptNo(),
                        outcome.workerResultId(), outcome.packId(),
                        outcome.activationRevision(), outcome.policyVersion(),
                        outcome.amount(), lines,
                        outcome.certifiedProviderEvidenceDigest(),
                        outcome.recordedAt());
        return new Pay003Models.PreparedSocialInsuranceFact(
                factId, canonicalOutcome,
                lines.stream().map(Pay003Models.ProviderSourceLine::resultLineId)
                        .toList(),
                sourceDigest, providerDigest, resultDigest);
    }

    void publish(Pay003Models.SocialInsuranceProviderOutcome outcome) {
        Pay003Models.PreparedSocialInsuranceFact prepared = prepare(outcome);
        transactions.executeWithoutResult(ignored ->
                insert(prepared));
    }

    private void insert(Pay003Models.PreparedSocialInsuranceFact prepared) {
        Pay003Models.SocialInsuranceProviderOutcome outcome = prepared.outcome();
        bindTenant(outcome.tenantId());
        try {
            jdbc.update("""
                    INSERT INTO pay_social_insurance_result_facts (
                        tenant_id, social_insurance_fact_id, run_id, attempt_no,
                        worker_result_id, pack_id, activation_revision,
                        policy_version, provider_result_type,
                        provider_source_semantics, provider_outcome_digest,
                        amount, currency, source_result_line_ids,
                        source_line_set_digest, result_digest, recorded_at)
                    VALUES (
                        :tenantId, :factId, :runId, :attemptNo,
                        :workerResultId, :packId, :activationRevision,
                        :policyVersion, 'SOCIAL_INSURANCE',
                        'TYPED_COUNTRY_RULE_OUTPUT', :providerOutcomeDigest,
                        :amount, :currency, :sourceLineIds,
                        :sourceLineSetDigest, :resultDigest, :recordedAt)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", outcome.tenantId())
                    .addValue("factId", prepared.socialInsuranceFactId())
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
                    .addValue("recordedAt", Timestamp.from(
                            outcome.recordedAt().atOffset(ZoneOffset.UTC)
                                    .toInstant())));
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "Social-insurance fact conflicts with immutable lineage.",
                    exception);
        }
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
