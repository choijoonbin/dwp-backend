package com.dwp.services.platform.workplace.connectorops;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkplaceConnectorRuntimeObservationConsumerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final WorkplaceConnectorOpsService service = mock(WorkplaceConnectorOpsService.class);
    private final WorkplaceConnectorRuntimeObservationConsumer consumer =
            new WorkplaceConnectorRuntimeObservationConsumer(
                    mapper, new DomainEventContractRegistry(), service);

    @Test
    void strictProviderEventIsTheOnlyRuntimeTruthIngress() {
        when(service.observeProvider(any())).thenReturn(true);
        assertThat(consumer.observe(event(data()))).isTrue();
        verify(service).observeProvider(argThat(observation ->
                observation.tenantId() == 77
                        && observation.kind()
                                == WorkplaceConnectorOpsDtos.ConnectorKind.CALENDAR
                        && observation.provider().equals("msgraph")
                        && observation.sequence() == 3));
    }

    @Test
    void unknownOrMissingProviderFieldsFailClosed() {
        ObjectNode unknown = data().put("operatorOverride", "HEALTHY");
        assertThatThrownBy(() -> consumer.observe(event(unknown)))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode missing = data();
        missing.remove("configurationVersion");
        assertThatThrownBy(() -> consumer.observe(event(missing)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    private DomainEventEnvelope event(ObjectNode data) {
        return DomainEventEnvelope.create(
                "urn:dwp:provider:workplace-connector:msgraph",
                WorkplaceConnectorRuntimeObservationConsumer.TYPE,
                1,
                77L,
                WorkplaceConnectorRuntimeObservationConsumer.AGGREGATE,
                "CALENDAR",
                3,
                "corr-runtime",
                null,
                null,
                data);
    }

    private ObjectNode data() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T03:00:00Z");
        ObjectNode data = mapper.createObjectNode();
        data.put("provider", "msgraph");
        data.put("configurationVersion", 2);
        data.put("adapterId", "calendar-adapter");
        data.put("adapterVersion", "1.0.0");
        data.put("reportedState", "HEALTHY");
        data.putArray("capabilities").add("HEALTH").add("REPLAY");
        data.put("sourceObservedAt", now.toString());
        data.put("lastSuccessAt", now.minusSeconds(3).toString());
        data.put("lagSeconds", 2);
        data.put("checkpointReference", "opaque");
        data.put("retryQueueDepth", 0);
        data.put("deadLetterQueueDepth", 0);
        data.putNull("errorCode");
        data.put("payloadFingerprint", "sha256:1234567890abcdef");
        return data;
    }
}
