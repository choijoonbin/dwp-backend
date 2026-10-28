package com.dwp.services.payroll.statutory.pay005;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Pay005ContractTest {

    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);
    private static final UUID RUN =
            UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID WORKER_RESULT =
            UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final UUID PACK =
            UUID.fromString("00000000-0000-0000-0000-000000000503");
    private static final UUID LOW =
            UUID.fromString("00000000-0000-0000-0000-000000000504");
    private static final UUID HIGH =
            UUID.fromString("f0000000-0000-0000-0000-000000000505");

    @Test
    void canonicalPreparationOrdersSourceLinesAndPreservesTypedZero() {
        Pay005TaxResultOutcomePublisher publisher = publisher();
        Pay005Models.SourceLine low = line(LOW, "0.0000", "GBP");
        Pay005Models.SourceLine high = line(HIGH, "0.0000", "GBP");

        Pay005Models.PreparedTaxResultFact first = publisher.prepare(
                outcome(List.of(high, low), "0.0000", "GBP", NOW));
        Pay005Models.PreparedTaxResultFact second = publisher.prepare(
                outcome(List.of(low, high), "0.0000", "GBP", NOW));

        assertThat(first).isEqualTo(second);
        assertThat(first.sourceResultLineIds()).containsExactly(LOW, HIGH);
        assertThat(first.outcome().amount().amount())
                .isEqualByComparingTo("0.0000");
        assertThat(first.sourceLineSetDigest()).matches("[0-9a-f]{64}");
        assertThat(first.providerOutcomeDigest()).matches("[0-9a-f]{64}");
        assertThat(first.resultDigest()).matches("[0-9a-f]{64}");
    }

    @Test
    void preparationRejectsGenericRelabelDuplicateRoundingAndReconciliation() {
        assertThatThrownBy(() -> new Pay005Models.SourceLine(
                LOW, new Pay005Models.PostedMoney(
                new BigDecimal("1.0000"), "GBP"), "DEDUCTION"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pay005Models.PostedMoney(
                new BigDecimal("1.00001"), "GBP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher().prepare(outcome(
                List.of(line(LOW, "5.0000", "GBP"),
                        line(LOW, "5.0000", "GBP")),
                "10.0000", "GBP", NOW)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> publisher().prepare(outcome(
                List.of(line(LOW, "9.9999", "GBP")),
                "10.0000", "GBP", NOW)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("reconcile");
        assertThatThrownBy(() -> publisher().prepare(outcome(
                List.of(line(LOW, "10.0000", "USD")),
                "10.0000", "GBP", NOW)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("reconcile");
    }

    @Test
    void canonicalDigestChangesForDifferentOutcomeIdentity() {
        Pay005TaxResultOutcomePublisher publisher = publisher();
        Pay005Models.PreparedTaxResultFact original = publisher.prepare(outcome(
                List.of(line(LOW, "10.0000", "GBP")),
                "10.0000", "GBP", NOW));
        Pay005Models.PreparedTaxResultFact changed = publisher.prepare(outcome(
                List.of(line(LOW, "10.0000", "GBP")),
                "10.0000", "GBP", NOW.plusSeconds(1)));
        assertThat(changed.resultDigest()).isNotEqualTo(original.resultDigest());
        assertThat(changed.taxResultFactId())
                .isNotEqualTo(original.taxResultFactId());
    }

    private Pay005Models.SourceLine line(
            UUID id, String amount, String currency) {
        return new Pay005Models.SourceLine(
                id, new Pay005Models.PostedMoney(
                new BigDecimal(amount), currency),
                "TYPED_COUNTRY_RULE_OUTPUT");
    }

    private Pay005Models.TaxResultProviderOutcome outcome(
            List<Pay005Models.SourceLine> lines,
            String amount,
            String currency,
            Instant recordedAt) {
        return new Pay005Models.TaxResultProviderOutcome(
                1, RUN, 1, WORKER_RESULT, PACK, 4, "policy-v1",
                new Pay005Models.PostedMoney(
                        new BigDecimal(amount), currency),
                lines, DIGEST, recordedAt);
    }

    private Pay005TaxResultOutcomePublisher publisher() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl("jdbc:h2:mem:not-used");
        return new Pay005TaxResultOutcomePublisher(
                new NamedParameterJdbcTemplate(source),
                new Pay005Canonical(JsonMapper.builder()
                        .findAndAddModules().build()));
    }
}
