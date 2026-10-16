package com.dwp.services.payroll.home;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.OMIT;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.VIEW;
import static com.dwp.services.payroll.home.PayrollHomeContributionProjectorTest.decisions;
import static com.dwp.services.payroll.home.PayrollHomeContributionProjectorTest.self;
import static com.dwp.services.payroll.home.PayrollHomeContributionProjectorTest.selfSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayrollHomePublicationSchedulerTest {

    @Test
    void producerActivationAlsoEnablesItsSchedulerInfrastructure() {
        assertThat(PayrollHomeProducerConfiguration.class.getAnnotation(EnableScheduling.class))
                .isNotNull();
    }

    @Test
    void atomicallyAcknowledgesOnlyTheExactPublishedSourceVersion() {
        PayrollHomePublicationCandidates candidates = mock(PayrollHomePublicationCandidates.class);
        UUID candidateId = UUID.fromString("00000000-0000-0000-0000-000000000911");
        var authority = self(decisions(OMIT, VIEW, VIEW, OMIT));
        when(candidates.lockNext()).thenReturn(Optional.of(
                new PayrollHomePublicationCandidates.Candidate(candidateId, 9, authority)));
        var codec = new PayrollHomePayloadCodec(
                "payroll-home-test-secret-32-bytes-minimum");
        var projector = new PayrollHomeContributionProjector(
                codec, Duration.ofMinutes(2), Duration.ofMinutes(10));
        var service = new PayrollHomeContributionService(
                mock(PayrollHomeAdmission.class), ignored -> selfSource(9), projector, codec,
                new PayrollHomeEventPublisher(
                        mock(DomainEventRecorder.class), new DomainEventContractRegistry(),
                        new ObjectMapper().findAndRegisterModules(), true),
                Clock.fixed(Instant.parse("2026-10-16T09:00:00Z"), ZoneOffset.UTC));

        new PayrollHomePublicationScheduler(candidates, service).publishNext();

        ArgumentCaptor<PayrollHomePublicationCandidates.PublicationReceipt> receipt =
                ArgumentCaptor.forClass(
                        PayrollHomePublicationCandidates.PublicationReceipt.class);
        verify(candidates).markPublished(receipt.capture());
        assertThat(receipt.getValue().candidateId()).isEqualTo(candidateId);
        assertThat(receipt.getValue().sourceVersion()).isEqualTo(9);
        assertThat(receipt.getValue().payloadDigest()).matches("^[0-9a-f]{64}$");
    }
}
