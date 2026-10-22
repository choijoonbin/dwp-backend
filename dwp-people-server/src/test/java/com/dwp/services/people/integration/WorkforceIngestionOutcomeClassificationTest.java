package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012CanonicalJson;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkforceIngestionOutcomeClassificationTest {
    @AfterEach
    void clearTransactionMarker() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void validationFailureIsRejectedBeforeAnyDomainEffect() {
        Fixture fixture = fixture();
        when(fixture.mappings().validateAndMap(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.MappingResult(
                        false, "TENANT_POLICY_REJECTED", "Record did not pass policy.", null));

        ProcessResult result = process(fixture);

        assertThat(result.item().outcome())
                .isEqualTo(Hrm012WorkforceIngestionContract.ItemOutcome.REJECTED);
        assertThat(result.item().findingCode()).isEqualTo("TENANT_POLICY_REJECTED");
        assertThat(result.item().redactedSummary()).isEqualTo("Record did not pass policy.");
        assertThat(result.counts())
                .isEqualTo(new Hrm012WorkforceIngestionContract.Counts(1, 0, 1, 0, 0, 0));
        verifyNoInteractions(fixture.effects(), fixture.quarantine());
    }

    @Test
    void acceptedRecordWithoutProtectableRestrictedValueFailsWholeRunRetryably() {
        Fixture fixture = fixture();
        when(fixture.mappings().validateAndMap(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.MappingResult(
                        true, null, null, null));
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> fixture.processor().process(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RUN, "worker-1"))
                .isInstanceOf(com.dwp.services.people.hris.contracts.hrm012
                        .Hrm012Problem.class)
                .satisfies(error -> {
                    var problem = (com.dwp.services.people.hris.contracts.hrm012
                            .Hrm012Problem) error;
                    assertThat(problem.status()).isEqualTo(503);
                    assertThat(problem.retryable()).isTrue();
                });
        verify(fixture.repository(), never()).reconcileApply(
                any(), any(), any(), anyString(), anyString(), anyString(), any());
        verifyNoInteractions(fixture.effects(), fixture.quarantine());
    }

    @Test
    void acceptedProtectableNonObjectIsQuarantinedAndNeverRejectedOrApplied()
            throws Exception {
        Fixture fixture = fixture();
        when(fixture.mappings().validateAndMap(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.MappingResult(
                        true, null, null, fixture.mapper().readTree("\"restricted\"")));
        when(fixture.quarantine().protect(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.ProtectedQuarantine(
                        new byte[]{4, 5, 6}, "kms://hrm012/key/1", "c".repeat(64)));

        ProcessResult result = process(fixture);

        assertThat(result.item().outcome()).isEqualTo(
                Hrm012WorkforceIngestionContract.ItemOutcome
                        .QUARANTINED_AFTER_ACCEPTANCE);
        assertThat(result.item().findingCode()).isEqualTo(
                "MAPPING_OUTPUT_QUARANTINED");
        assertThat(result.counts()).isEqualTo(
                new Hrm012WorkforceIngestionContract.Counts(1, 1, 0, 0, 1, 0));
        verifyNoInteractions(fixture.effects());
    }

    @Test
    void deterministicPriorOrSameRunDuplicateIsOutsideAcceptedAccounting() {
        Fixture fixture = fixture();
        when(fixture.repository().duplicate(anyLong(), any(), any(), anyString(),
                anyString(), anyString())).thenReturn(true);

        ProcessResult result = process(fixture);

        assertThat(result.item().outcome())
                .isEqualTo(Hrm012WorkforceIngestionContract.ItemOutcome.DUPLICATE);
        assertThat(result.counts())
                .isEqualTo(new Hrm012WorkforceIngestionContract.Counts(1, 0, 0, 1, 0, 0));
        verifyNoInteractions(fixture.mappings(), fixture.effects(), fixture.quarantine());
    }

    @Test
    void jcsEquivalentOneAndOnePointZeroCannotBypassSameRunDuplicateDetection()
            throws Exception {
        Fixture fixture = fixture();
        byte[] envelope = ("{\"schemaKey\":\"workforce.v1\",\"schemaVersion\":1,"
                + "\"records\":[{\"sourceRecordKey\":\"same\","
                + "\"sourceVersion\":\"v1\",\"payload\":{\"value\":1}},"
                + "{\"sourceRecordKey\":\"same\",\"sourceVersion\":\"v1\","
                + "\"payload\":{\"value\":1.0}}]}")
                .getBytes(StandardCharsets.UTF_8);
        Hrm012WorkforceIngestionRepository.Run base =
                Hrm012TestFixtures.run("RUNNING", null, null);
        Hrm012WorkforceIngestionRepository.Run run = withObjectDigest(
                base, Hrm012CanonicalJson.sha256(envelope));
        when(fixture.repository().claim(anyLong(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of(run));
        when(fixture.artifacts().fetch(any())).thenReturn(
                new Hrm012WorkforceIngestionContract.Artifact(
                        run.objectRef(), run.expectedObjectVersion(), envelope));
        when(fixture.mappings().validateAndMap(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.MappingResult(
                        true, null, null,
                        fixture.mapper().readTree("{\"person\":\"restricted\"}")));
        when(fixture.effects().apply(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.EffectResult(
                        true, "e".repeat(64), null));
        TransactionSynchronizationManager.setActualTransactionActive(true);

        fixture.processor().process(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RUN, "worker-1");

        ArgumentCaptor<List<Hrm012WorkforceIngestionContract.ItemEvidence>> items =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Hrm012WorkforceIngestionContract.Counts> counts =
                ArgumentCaptor.forClass(Hrm012WorkforceIngestionContract.Counts.class);
        verify(fixture.repository()).reconcileApply(any(), items.capture(),
                counts.capture(), anyString(), anyString(), anyString(), any());
        assertThat(items.getValue())
                .extracting(Hrm012WorkforceIngestionContract.ItemEvidence::outcome)
                .containsExactly(Hrm012WorkforceIngestionContract.ItemOutcome.APPLIED,
                        Hrm012WorkforceIngestionContract.ItemOutcome.DUPLICATE);
        assertThat(items.getValue().get(0).inputDigest())
                .isEqualTo(items.getValue().get(1).inputDigest());
        assertThat(counts.getValue()).isEqualTo(
                new Hrm012WorkforceIngestionContract.Counts(2, 1, 0, 1, 0, 1));
    }

    @Test
    void acceptedApplyProducesOnlyDigestEvidenceAndNoRestrictedMappedValue() throws Exception {
        Fixture fixture = fixture();
        when(fixture.mappings().validateAndMap(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.MappingResult(true, null, null,
                        fixture.mapper().readTree("{\"person\":\"restricted\"}")));
        when(fixture.effects().apply(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.EffectResult(
                        true, "e".repeat(64), null));

        ProcessResult result = process(fixture);

        assertThat(result.item().outcome())
                .isEqualTo(Hrm012WorkforceIngestionContract.ItemOutcome.APPLIED);
        assertThat(result.item().appliedEffectDigest()).isEqualTo("e".repeat(64));
        assertThat(result.item().findingCode()).isNull();
        assertThat(result.item().redactedSummary()).isNull();
        assertThat(result.item().toString()).doesNotContain("restricted", "employee-1");
        assertThat(result.counts())
                .isEqualTo(new Hrm012WorkforceIngestionContract.Counts(1, 1, 0, 0, 0, 1));
        verify(fixture.quarantine(), never()).protect(any());
    }

    @Test
    void onlyFailureAfterAcceptanceBecomesEncryptedQuarantine() throws Exception {
        Fixture fixture = fixture();
        when(fixture.mappings().validateAndMap(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.MappingResult(true, null, null,
                        fixture.mapper().readTree("{\"person\":\"restricted\"}")));
        when(fixture.effects().apply(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.EffectResult(
                        false, null, "Destination rejected the accepted record."));
        when(fixture.quarantine().protect(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.ProtectedQuarantine(
                        new byte[]{1, 2, 3}, "kms://hrm012/key/1", "c".repeat(64)));

        ProcessResult result = process(fixture);

        assertThat(result.item().outcome()).isEqualTo(
                Hrm012WorkforceIngestionContract.ItemOutcome
                        .QUARANTINED_AFTER_ACCEPTANCE);
        assertThat(result.item().findingCode()).isEqualTo("APPLY_QUARANTINED");
        assertThat(result.item().quarantineCiphertext()).containsExactly(1, 2, 3);
        assertThat(result.item().quarantineKeyReference()).isEqualTo("kms://hrm012/key/1");
        assertThat(result.counts())
                .isEqualTo(new Hrm012WorkforceIngestionContract.Counts(1, 1, 0, 0, 1, 0));
    }

    @Test
    void quarantineKeyReferenceMatchesTheV60StorageBoundary() throws Exception {
        Fixture accepted = fixture();
        when(accepted.mappings().validateAndMap(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.MappingResult(true, null, null,
                        accepted.mapper().readTree("{\"person\":\"restricted\"}")));
        when(accepted.effects().apply(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.EffectResult(
                        false, null, "Destination rejected the accepted record."));
        when(accepted.quarantine().protect(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.ProtectedQuarantine(
                        new byte[]{1}, "k".repeat(255), "c".repeat(64)));

        assertThat(process(accepted).item().quarantineKeyReference()).hasSize(255);

        Fixture overlong = fixture();
        when(overlong.mappings().validateAndMap(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.MappingResult(true, null, null,
                        overlong.mapper().readTree("{\"person\":\"restricted\"}")));
        when(overlong.effects().apply(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.EffectResult(
                        false, null, "Destination rejected the accepted record."));
        when(overlong.quarantine().protect(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.ProtectedQuarantine(
                        new byte[]{1}, "k".repeat(256), "c".repeat(64)));
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> overlong.processor().process(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RUN, "worker-1"))
                .isInstanceOf(com.dwp.services.people.hris.contracts.hrm012
                        .Hrm012Problem.class)
                .satisfies(error -> {
                    var problem = (com.dwp.services.people.hris.contracts.hrm012
                            .Hrm012Problem) error;
                    assertThat(problem.status()).isEqualTo(503);
                    assertThat(problem.retryable()).isTrue();
                });
        verify(overlong.repository(), never()).reconcileApply(
                any(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    private ProcessResult process(Fixture fixture) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        fixture.processor().process(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RUN, "worker-1");
        ArgumentCaptor<List<Hrm012WorkforceIngestionContract.ItemEvidence>> items =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Hrm012WorkforceIngestionContract.Counts> counts =
                ArgumentCaptor.forClass(Hrm012WorkforceIngestionContract.Counts.class);
        verify(fixture.repository()).reconcileApply(any(), items.capture(),
                counts.capture(), anyString(), anyString(), anyString(), any());
        return new ProcessResult(items.getValue().getFirst(), counts.getValue());
    }

    private Fixture fixture() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Hrm012WorkforceIngestionRepository repository =
                mock(Hrm012WorkforceIngestionRepository.class);
        when(repository.claim(anyLong(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of(Hrm012TestFixtures.run("RUNNING", null, null)));
        when(repository.duplicate(anyLong(), any(), any(), anyString(),
                anyString(), anyString())).thenReturn(false);
        Hrm012WorkforceIngestionPorts.ArtifactPort artifacts =
                mock(Hrm012WorkforceIngestionPorts.ArtifactPort.class);
        when(artifacts.fetch(any())).thenReturn(
                new Hrm012WorkforceIngestionContract.Artifact(
                        Hrm012TestFixtures.command().objectRef(), 3,
                        Hrm012TestFixtures.ENVELOPE));
        Hrm012WorkforceIngestionPorts.MappingPort mappings =
                mock(Hrm012WorkforceIngestionPorts.MappingPort.class);
        Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort effects =
                mock(Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort.class);
        Hrm012WorkforceIngestionPorts.QuarantineProtectorPort quarantine =
                mock(Hrm012WorkforceIngestionPorts.QuarantineProtectorPort.class);
        Hrm012WorkforceIngestionProcessor processor =
                new Hrm012WorkforceIngestionProcessor(
                        repository, artifacts, mappings, effects, quarantine, mapper,
                        Clock.fixed(Hrm012TestFixtures.NOW, ZoneOffset.UTC));
        return new Fixture(processor, repository, artifacts, mappings, effects,
                quarantine, mapper);
    }

    private Hrm012WorkforceIngestionRepository.Run withObjectDigest(
            Hrm012WorkforceIngestionRepository.Run run,
            String objectDigest) {
        return new Hrm012WorkforceIngestionRepository.Run(
                run.runId(), run.runPublicId(), run.tenantId(), run.receiptId(),
                run.linkedReplayReceiptId(), run.linkedReplayRunId(),
                run.connectorId(), run.connectorPublicId(), run.connectorVersion(),
                run.mappingId(), run.mappingPublicId(), run.mappingVersion(),
                run.mappingSha256(), run.legalEntityPublicIds(),
                run.populationScopeDigest(), run.objectRef(), objectDigest, run.schemaKey(),
                run.schemaVersion(), run.mode(), run.effectiveDate(), run.state(),
                run.cursorBeforeDigest(), run.cursorExpectedVersion(), run.version(),
                run.actorPublicId(), run.correlationId(), run.expectedObjectVersion(),
                run.createdAt());
    }

    private record Fixture(
            Hrm012WorkforceIngestionProcessor processor,
            Hrm012WorkforceIngestionRepository repository,
            Hrm012WorkforceIngestionPorts.ArtifactPort artifacts,
            Hrm012WorkforceIngestionPorts.MappingPort mappings,
            Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort effects,
            Hrm012WorkforceIngestionPorts.QuarantineProtectorPort quarantine,
            ObjectMapper mapper) {
    }

    private record ProcessResult(
            Hrm012WorkforceIngestionContract.ItemEvidence item,
            Hrm012WorkforceIngestionContract.Counts counts) {
    }
}
