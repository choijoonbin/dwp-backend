package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class Sys008OperationsExceptionOutboxAdapterTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsAContractValidDeterministicSysEvt009OutboxEnvelope()
            throws Exception {
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        Sys008OperationsExceptionOutboxAdapter adapter =
                new Sys008OperationsExceptionOutboxAdapter(
                        recorder, contracts, json);
        UUID executionId = UUID.fromString(
                "90000000-0000-4000-8000-000000000001");
        OffsetDateTime observedAt = OffsetDateTime.of(
                2026, 10, 26, 4, 30, 0, 0, ZoneOffset.UTC);
        var exception = new IntegrationRuntimePorts.OperationsException(
                71, executionId, "STALE_RECONCILIATION_CLAIM",
                "a".repeat(64), observedAt);

        adapter.record(exception);

        ArgumentCaptor<DomainEventEnvelope> event =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(event.capture());
        assertThat(event.getValue().id()).isEqualTo(exception.eventId());
        assertThat(event.getValue().source())
                .isEqualTo(Sys008OperationsExceptionOutboxAdapter.SOURCE);
        assertThat(event.getValue().type())
                .isEqualTo(Sys008OperationsExceptionOutboxAdapter.TYPE);
        assertThat(event.getValue().aggregateSequence()).isEqualTo(1);
        assertThat(event.getValue().correlationId())
                .isEqualTo(executionId.toString());
        assertThat(event.getValue().data().path("evidenceRefs").get(0).asText())
                .isEqualTo(exception.evidenceReference());

        Object registry = new Sys008OperationsExceptionSummaryKeyRegistryFactory()
                .getObject();
        Class<?> contractType = Class.forName(
                "com.dwp.services.platform.hrisconfiguration.operations."
                        + "HrisOperationalExceptionEventContract");
        Constructor<?> constructor = contractType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object consumerContract = constructor.newInstance(json, registry);
        Method validate = contractType.getDeclaredMethod(
                "validate", DomainEventEnvelope.class);
        validate.setAccessible(true);
        Object validated = validate.invoke(consumerContract, event.getValue());
        Method publicId = validated.getClass()
                .getDeclaredMethod("exceptionPublicId");
        publicId.setAccessible(true);
        assertThat(publicId.invoke(validated)).isEqualTo(exception.eventId());
        assertThat(contracts.snapshot())
                .containsKey(Sys008OperationsExceptionOutboxAdapter.TYPE);
    }

    @Test
    void rejectsUnsealedCategoriesInsteadOfSilentlyDroppingEvidence() {
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        Sys008OperationsExceptionOutboxAdapter adapter =
                new Sys008OperationsExceptionOutboxAdapter(
                        recorder, new DomainEventContractRegistry(), json);

        assertThatThrownBy(() -> adapter.record(
                new IntegrationRuntimePorts.OperationsException(
                        71, UUID.randomUUID(), "UNAPPROVED_CATEGORY",
                        "b".repeat(64), OffsetDateTime.now(ZoneOffset.UTC))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SYS008_OPERATION_EXCEPTION_CATEGORY_NOT_SEALED");
        verifyNoInteractions(recorder);
    }
}
