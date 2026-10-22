package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkforceIngestionValidateOnlyAccountingTest {
    @AfterEach
    void clearTransactionMarker() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateOnlyPersistsValidationEvidenceWithoutEffectsCursorOrSuccessEvent()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Hrm012WorkforceIngestionRepository repository =
                mock(Hrm012WorkforceIngestionRepository.class);
        Hrm012WorkforceIngestionRepository.Run apply =
                Hrm012TestFixtures.run("RUNNING", null, null);
        Hrm012WorkforceIngestionRepository.Run validate =
                new Hrm012WorkforceIngestionRepository.Run(
                        apply.runId(), apply.runPublicId(), apply.tenantId(), apply.receiptId(),
                        null, null, apply.connectorId(), apply.connectorPublicId(),
                        apply.connectorVersion(), apply.mappingId(), apply.mappingPublicId(),
                        apply.mappingVersion(), apply.mappingSha256(),
                        apply.legalEntityPublicIds(), apply.populationScopeDigest(),
                        apply.objectRef(),
                        apply.objectDigest(), apply.schemaKey(), apply.schemaVersion(),
                        Hrm012WorkforceIngestionContract.Mode.VALIDATE_ONLY,
                        apply.effectiveDate(), apply.state(), apply.cursorBeforeDigest(),
                        apply.cursorExpectedVersion(), apply.version(), apply.actorPublicId(),
                        apply.correlationId(), apply.expectedObjectVersion(), apply.createdAt());
        when(repository.claim(anyLong(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of(validate));
        when(repository.duplicate(anyLong(), any(), any(), anyString(),
                anyString(), anyString())).thenReturn(false);
        Hrm012WorkforceIngestionPorts.ArtifactPort artifacts =
                mock(Hrm012WorkforceIngestionPorts.ArtifactPort.class);
        when(artifacts.fetch(any())).thenReturn(
                new Hrm012WorkforceIngestionContract.Artifact(
                        validate.objectRef(), validate.expectedObjectVersion(),
                        Hrm012TestFixtures.ENVELOPE));
        Hrm012WorkforceIngestionPorts.MappingPort mappings =
                mock(Hrm012WorkforceIngestionPorts.MappingPort.class);
        when(mappings.validateAndMap(any())).thenReturn(
                new Hrm012WorkforceIngestionPorts.MappingResult(
                        true, null, null, mapper.readTree("{\"person\":\"restricted\"}")));
        Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort effects =
                mock(Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort.class);
        Hrm012WorkforceIngestionPorts.QuarantineProtectorPort quarantine =
                mock(Hrm012WorkforceIngestionPorts.QuarantineProtectorPort.class);
        Hrm012WorkforceIngestionProcessor processor =
                new Hrm012WorkforceIngestionProcessor(
                        repository, artifacts, mappings, effects, quarantine, mapper,
                        Clock.fixed(Hrm012TestFixtures.NOW, ZoneOffset.UTC));
        TransactionSynchronizationManager.setActualTransactionActive(true);

        processor.process(Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RUN, "worker-1");

        ArgumentCaptor<List<Hrm012WorkforceIngestionContract.ItemEvidence>> items =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Hrm012WorkforceIngestionContract.Counts> counts =
                ArgumentCaptor.forClass(Hrm012WorkforceIngestionContract.Counts.class);
        verify(repository).reconcileValidation(any(), items.capture(), counts.capture(),
                anyString(), any());
        assertThat(items.getValue()).singleElement()
                .extracting(Hrm012WorkforceIngestionContract.ItemEvidence::outcome)
                .isEqualTo(Hrm012WorkforceIngestionContract.ItemOutcome.VALIDATED);
        assertThat(counts.getValue())
                .isEqualTo(new Hrm012WorkforceIngestionContract.Counts(1, 0, 0, 0, 0, 0));
        verifyNoInteractions(effects, quarantine);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .reconcileApply(any(), any(), any(), anyString(), anyString(),
                        anyString(), any());
    }
}
