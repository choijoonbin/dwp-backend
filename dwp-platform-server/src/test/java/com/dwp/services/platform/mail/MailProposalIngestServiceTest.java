package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.services.platform.mail.MailTypes.ProposalType.ESCALATE_NOTIFICATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailProposalIngestServiceTest {

    private static final String SOURCE_HASH = "a".repeat(64);

    @Mock
    private MailProposalIngestRepository repository;
    @Mock
    private MailCommandRepository evidence;

    private MailProposalIngestService service;

    @BeforeEach
    void setUp() {
        service = new MailProposalIngestService(
                repository, evidence, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void persistsOnlyAnAccessibleInboundMessageWithCurrentEvidence() {
        MailProposalIngestDtos.Request request = request(UUID.randomUUID(), "Notify owner");
        MailProposalIngestRepository.Source source = new MailProposalIngestRepository.Source(
                UUID.randomUUID(), request.sourceMessageId(), SOURCE_HASH);
        when(repository.source(1L, 7L, request.threadId(), request.sourceMessageId()))
                .thenReturn(Optional.of(source));
        when(repository.existing(
                1L, MailProposalIngestService.PRODUCER_IDENTITY, request.producerEventId()))
                .thenReturn(Optional.empty());
        when(repository.insert(
                any(UUID.class), eq(1L), eq(7L), eq(source),
                eq(MailProposalIngestService.PRODUCER_IDENTITY), eq(request),
                any(MailAiActionCatalog.Policy.class), anyString()))
                .thenReturn(1);

        MailProposalIngestDtos.Receipt receipt = service.ingest(
                MailProposalIngestService.PRODUCER_IDENTITY,
                1L, 7L, "corr-produce", request);

        assertThat(receipt.state()).isEqualTo("PROPOSED");
        assertThat(receipt.replayed()).isFalse();
        assertThat(receipt.version()).isZero();
        verify(evidence).audit(
                eq(1L), eq(7L), eq("mail.proposal.produced"),
                eq("MAIL_ACTION_PROPOSAL"), eq(receipt.proposalId().toString()),
                eq("corr-produce"), eq(Map.of()), anyMap());
        verify(evidence).domainEvent(
                eq(1L), eq("MAIL_ACTION_PROPOSAL"), eq(receipt.proposalId()),
                eq("mail.proposal.produced"), anyMap(), eq("corr-produce"));
    }

    @Test
    void exactProducerReplayReturnsTheOriginalReceiptWithoutDuplicateEvidence() {
        MailProposalIngestDtos.Request request = request(UUID.randomUUID(), "Notify owner");
        MailProposalIngestRepository.Source source = new MailProposalIngestRepository.Source(
                UUID.randomUUID(), request.sourceMessageId(), SOURCE_HASH);
        AtomicReference<String> fingerprint = new AtomicReference<>();
        UUID storedId = UUID.randomUUID();
        when(repository.source(1L, 7L, request.threadId(), request.sourceMessageId()))
                .thenReturn(Optional.of(source));
        when(repository.existing(
                1L, MailProposalIngestService.PRODUCER_IDENTITY, request.producerEventId()))
                .thenAnswer(invocation -> fingerprint.get() == null
                        ? Optional.empty()
                        : Optional.of(new MailProposalIngestRepository.Existing(
                                storedId, fingerprint.get(), "PROPOSED", 0L)));
        when(repository.insert(
                any(UUID.class), anyLong(), anyLong(), any(), anyString(), any(), any(), anyString()))
                .thenAnswer(invocation -> {
                    fingerprint.set(invocation.getArgument(7));
                    return 1;
                });

        MailProposalIngestDtos.Receipt created = service.ingest(
                MailProposalIngestService.PRODUCER_IDENTITY,
                1L, 7L, "corr-produce", request);
        MailProposalIngestDtos.Receipt replayed = service.ingest(
                MailProposalIngestService.PRODUCER_IDENTITY,
                1L, 7L, "corr-retry", request);

        assertThat(created.replayed()).isFalse();
        assertThat(replayed)
                .isEqualTo(new MailProposalIngestDtos.Receipt(
                        storedId, "PROPOSED", true, 0L));
        assertThat(fingerprint.get()).matches("[a-f0-9]{64}");
        verify(repository, times(1)).insert(
                any(UUID.class), anyLong(), anyLong(), any(), anyString(), any(), any(), anyString());
        verify(evidence, times(1)).audit(
                anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyMap(), anyMap());
    }

    @Test
    void rejectsAProducerEventReusedWithDifferentEvidence() {
        UUID eventId = UUID.randomUUID();
        MailProposalIngestDtos.Request first = request(eventId, "Notify owner");
        MailProposalIngestDtos.Request changed = request(eventId, "Changed title");
        MailProposalIngestRepository.Source source = new MailProposalIngestRepository.Source(
                UUID.randomUUID(), first.sourceMessageId(), SOURCE_HASH);
        AtomicReference<String> fingerprint = new AtomicReference<>();
        when(repository.source(1L, 7L, first.threadId(), first.sourceMessageId()))
                .thenReturn(Optional.of(source));
        when(repository.existing(
                1L, MailProposalIngestService.PRODUCER_IDENTITY, eventId))
                .thenAnswer(invocation -> fingerprint.get() == null
                        ? Optional.empty()
                        : Optional.of(new MailProposalIngestRepository.Existing(
                                UUID.randomUUID(), fingerprint.get(), "PROPOSED", 0L)));
        when(repository.insert(
                any(UUID.class), anyLong(), anyLong(), any(), anyString(), any(), any(), anyString()))
                .thenAnswer(invocation -> {
                    fingerprint.set(invocation.getArgument(7));
                    return 1;
                });
        service.ingest(MailProposalIngestService.PRODUCER_IDENTITY,
                1L, 7L, "corr-first", first);

        assertThatThrownBy(() -> service.ingest(
                MailProposalIngestService.PRODUCER_IDENTITY,
                1L, 7L, "corr-changed", changed))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different proposal evidence");
    }

    @Test
    void rejectsForgedSourceEvidenceAndUnknownServiceIdentity() {
        MailProposalIngestDtos.Request request = request(UUID.randomUUID(), "Notify owner");
        when(repository.source(1L, 7L, request.threadId(), request.sourceMessageId()))
                .thenReturn(Optional.of(new MailProposalIngestRepository.Source(
                        UUID.randomUUID(), request.sourceMessageId(), "b".repeat(64))));

        assertThatThrownBy(() -> service.ingest(
                MailProposalIngestService.PRODUCER_IDENTITY,
                1L, 7L, "corr-forged", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("does not match");
        verify(repository, never()).insert(
                any(UUID.class), anyLong(), anyLong(), any(), anyString(), any(), any(), anyString());

        org.mockito.Mockito.reset(repository, evidence);
        assertThatThrownBy(() -> service.ingest(
                "untrusted-runtime", 1L, 7L, "corr-forbidden", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Agent runtime service identity");
        verifyNoInteractions(repository, evidence);
    }

    private MailProposalIngestDtos.Request request(UUID producerEventId, String title) {
        UUID threadId = UUID.nameUUIDFromBytes(
                ("thread:" + producerEventId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID messageId = UUID.nameUUIDFromBytes(
                ("message:" + producerEventId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new MailProposalIngestDtos.Request(
                producerEventId,
                threadId,
                messageId,
                ESCALATE_NOTIFICATION,
                1,
                title,
                "Escalate the reviewed message without exposing its content.",
                List.of(Map.of("kind", "MESSAGE", "hash", SOURCE_HASH)),
                Map.of(
                        "channel", "IN_APP",
                        "urgency", "HIGH",
                        "requiresConfirmation", true),
                new BigDecimal("0.91"),
                "LOW",
                "/mail/actions",
                OffsetDateTime.now().plusHours(2),
                "gpt-mail-agent",
                "2026-10-28",
                "mail-proposal-v3",
                "mail-action-policy-v1",
                List.of(SOURCE_HASH));
    }
}
