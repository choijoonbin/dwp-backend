package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractCanonicalizer;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Hrm011EventOutboxTest {
    private static final UUID AGGREGATE = UUID.fromString(
            "11111111-1111-4111-8111-111111111111");
    private static final UUID WORKER = UUID.fromString(
            "22222222-2222-4222-8222-222222222222");
    private static final UUID CORRELATION = UUID.fromString(
            "33333333-3333-4333-8333-333333333333");
    private static final UUID CAUSATION = UUID.fromString(
            "44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");

    @Test
    void xcon026HasDeterministicIdentityDigestAndAllEightEqualities() {
        Hrm011EventOutbox mapper = mapper();
        Hrm011EventOutbox.ContractIssued change = contractIssued();

        DomainEventEnvelope first = mapper.employmentContract(change);
        DomainEventEnvelope replay = mapper.employmentContract(change);

        Hrm011EventOutbox.requireEquality(first);
        assertThat(first.id()).isEqualTo(replay.id());
        assertThat(first.data().get("payloadDigest").textValue())
                .isEqualTo(replay.data().get("payloadDigest").textValue())
                .matches("[0-9a-f]{64}");
        assertThat(first.aggregateId()).isEqualTo(AGGREGATE.toString());
        assertThat(first.aggregateSequence()).isEqualTo(1);
        assertThat(first.data().has("newAggregateVersion")).isFalse();
    }

    @Test
    void xcon023To025NeverContainRestrictedPayloadValues() {
        Hrm011EventOutbox mapper = mapper();
        DomainEventEnvelope dependent = mapper.dependent(
                new Hrm011EventOutbox.DependentChange(
                        71L, AGGREGATE, WORKER, LocalDate.parse("2026-01-01"),
                        null, 1, CORRELATION, CAUSATION, NOW));
        DomainEventEnvelope tax = mapper.taxIdentity(
                new Hrm011EventOutbox.RestrictedChange(
                        71L, AGGREGATE, WORKER, LocalDate.parse("2026-01-01"),
                        null, 1, CORRELATION, CAUSATION, NOW));
        DomainEventEnvelope bank = mapper.bankAccountToken(
                new Hrm011EventOutbox.RestrictedChange(
                        71L, AGGREGATE, WORKER, LocalDate.parse("2026-01-01"),
                        null, 1, CORRELATION, CAUSATION, NOW));

        for (DomainEventEnvelope event : java.util.List.of(dependent, tax, bank)) {
            Hrm011EventOutbox.requireEquality(event);
            String json = event.data().toString();
            assertThat(json).doesNotContain(
                    "eligibilityFacts", "taxIdentifier", "bankAccountToken",
                    "routing", "maskedAccountDisplay");
        }
    }

    @Test
    void replayConflictStaleAndGapPolicyIsClosed() {
        Hrm011EventOutbox.ReplayLedger ledger = new Hrm011EventOutbox.ReplayLedger();
        UUID firstEvent = UUID.fromString("55555555-5555-4555-8555-555555555555");

        assertThat(ledger.evaluate(firstEvent, AGGREGATE, 1, "a".repeat(64)))
                .isEqualTo(Hrm011EventOutbox.Disposition.ACCEPTED);
        assertThat(ledger.evaluate(firstEvent, AGGREGATE, 1, "a".repeat(64)))
                .isEqualTo(Hrm011EventOutbox.Disposition.DUPLICATE);
        assertThat(ledger.evaluate(firstEvent, AGGREGATE, 1, "b".repeat(64)))
                .isEqualTo(Hrm011EventOutbox.Disposition.CONFLICT_QUARANTINED);
        assertThat(ledger.evaluate(UUID.randomUUID(), AGGREGATE, 1, "c".repeat(64)))
                .isEqualTo(Hrm011EventOutbox.Disposition.STALE_IGNORED);
        assertThat(ledger.evaluate(UUID.randomUUID(), AGGREGATE, 3, "d".repeat(64)))
                .isEqualTo(Hrm011EventOutbox.Disposition.GAP_BLOCKED_REFETCH_REQUIRED);
    }

    private Hrm011EventOutbox mapper() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        return new Hrm011EventOutbox(
                objectMapper, new EmploymentContractCanonicalizer(objectMapper));
    }

    private Hrm011EventOutbox.ContractIssued contractIssued() {
        return new Hrm011EventOutbox.ContractIssued(
                71L, AGGREGATE, WORKER, "STANDARD",
                LocalDate.parse("2026-01-01"), null,
                new EmploymentContractDtos.DocumentReference(
                        UUID.fromString("66666666-6666-4666-8666-666666666666"),
                        1, "e".repeat(64)),
                1, CORRELATION, CAUSATION, NOW);
    }
}
