package com.dwp.services.payroll.home;

import com.dwp.contracts.hris.xcon.v1.Xcon015PayrollHomeContributionChangedV1;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.OMIT;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.VIEW;
import static com.dwp.services.payroll.home.PayrollHomeContributionProjectorTest.decisions;
import static com.dwp.services.payroll.home.PayrollHomeContributionProjectorTest.self;
import static com.dwp.services.payroll.home.PayrollHomeContributionProjectorTest.selfSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PayrollHomeEventPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsGeneratedXcon015WithDeterministicReplayIdentity() throws Exception {
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        DomainEventContractRegistry registry = new DomainEventContractRegistry();
        var publisher = new PayrollHomeEventPublisher(recorder, registry, mapper, true);
        var value = materialization();

        publisher.record(7, value);
        publisher.record(7, value);

        ArgumentCaptor<DomainEventEnvelope> events =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder, times(2)).record(events.capture());
        assertThat(events.getAllValues()).extracting(DomainEventEnvelope::id)
                .containsExactly(events.getValue().id(), events.getValue().id());
        DomainEventEnvelope event = events.getValue();
        assertThat(event.type()).isEqualTo(PayrollHomeEventPublisher.EVENT_TYPE);
        assertThat(event.aggregateSequence()).isEqualTo(value.sourceVersion());
        assertThat(event.extensions()).containsEntry("xcon", "XCON-015");
        var xcon = mapper.treeToValue(
                event.data(), Xcon015PayrollHomeContributionChangedV1.class);
        assertThat(xcon.module()).isEqualTo("PAY");
        assertThat(xcon.payloadDigest()).isEqualTo(value.payloadDigest());
        assertThat(registry.snapshot()).containsKey(PayrollHomeEventPublisher.EVENT_TYPE);
    }

    @Test
    void publicationFlagLeavesTransactionalOutboxUntouchedWhenOff() {
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        var publisher = new PayrollHomeEventPublisher(
                recorder, new DomainEventContractRegistry(), mapper, false);

        publisher.record(7, materialization());

        verify(recorder, never()).record(org.mockito.ArgumentMatchers.any());
    }

    private HrisHomeContributionMaterializationV1 materialization() {
        var codec = new PayrollHomePayloadCodec(
                "payroll-home-test-secret-32-bytes-minimum", mapper);
        var projector = new PayrollHomeContributionProjector(
                codec, Duration.ofMinutes(2), Duration.ofMinutes(10));
        return projector.project(self(decisions(OMIT, VIEW, VIEW, OMIT)), selfSource(9));
    }
}
