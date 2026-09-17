package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.BookingCandidate;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.BookingIntentPreview;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.IntentItemPreview;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationService;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.CommandRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.ProposalRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.RequestRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.IntentItemDecision.AVAILABLE;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.IntentState.PREVIEWED;
import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkplaceAssistantBookingServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-16T12:00:00Z");

    @Test
    void decodedTrustedNameIsForwardedToAuthoritativeBookingPreview() {
        WorkplaceAssistantRepository repository = mock(WorkplaceAssistantRepository.class);
        WorkplaceAssistantAuditRepository audit = mock(WorkplaceAssistantAuditRepository.class);
        WorkplaceAssistantCommandCoordinator coordinator = mock(
                WorkplaceAssistantCommandCoordinator.class);
        WorkplaceBookingOrchestrationService authority = mock(
                WorkplaceBookingOrchestrationService.class);
        WorkplaceAssistantSupport support = mock(WorkplaceAssistantSupport.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorkplaceAssistantBookingService service = new WorkplaceAssistantBookingService(
                repository, audit, coordinator, authority,
                new WorkplaceAssistantRedactor(), mapper, support);

        long tenantId = 42L;
        long actorId = 99L;
        UUID personId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID intentItemId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID spoofedPersonId = UUID.randomUUID();
        UUID spoofedGrantId = UUID.randomUUID();
        RequestedBookingItem requestedItem = new RequestedBookingItem(
                "desk-one", actorId + 100, spoofedPersonId, "Spoofed beneficiary",
                spoofedGrantId,
                ResourceType.DESK, resourceId, null, null, NOW.plusDays(1),
                NOW.plusDays(1).plusHours(1), "Focus", true, false, List.of());
        ProposalRow proposal = new ProposalRow(
                proposalId, tenantId, requestId, 0, "desk-one", requestedItem,
                "User supplied constraints", List.of(), List.of(), PolicyResult.UNVALIDATED,
                List.of(), List.of(), null, null, null, null, null, 1, NOW, NOW);
        RequestRow current = new RequestRow(
                requestId, tenantId, actorId, RequestState.SUGGESTED, "book a desk",
                RedactionState.NOT_REQUIRED, true, false, "DWP_STRUCTURED_ASSISTANT",
                "model-v1", "prompt-v1", "tool-v1", mapper.createArrayNode(), null,
                null, null, false, null, mapper.createArrayNode(), "create-key",
                "a".repeat(64), "corr-create", 1, NOW.plusDays(30), null, NOW, NOW);
        CommandRow command = new CommandRow(
                UUID.randomUUID(), tenantId, actorId, requestId, "VALIDATE_REQUEST",
                "validate-key", "b".repeat(64), CommandState.ACCEPTED,
                "/v1/workplace/assistant/requests/" + requestId, "Validate", "corr-23",
                null, NOW, null);
        BookingCandidate candidate = new BookingCandidate(
                resourceId, null, "Desk 1", ResourceType.DESK, null, null,
                "Asia/Seoul", false, List.of(), null, true, 3);
        IntentItemPreview item = new IntentItemPreview(
                intentItemId, "desk-one", actorId, actorId, personId, "신뢰 사용자",
                null, ResourceType.DESK, requestedItem.startsAt(), requestedItem.endsAt(),
                AVAILABLE, "AVAILABLE", List.of(candidate), 2);
        BookingIntentPreview preview = new BookingIntentPreview(
                intentId, PREVIEWED, actorId, 120, true, "Validate", List.of(item),
                List.of(), List.of(), 4, NOW);

        when(support.fingerprint(any())).thenReturn("b".repeat(64));
        when(support.requireEnabledGovernance(tenantId)).thenReturn(null);
        when(support.requireRequestForUpdate(tenantId, actorId, requestId)).thenReturn(current);
        when(support.select(anyList(), eq(SelectionMode.ALL), eq(List.of())))
                .thenReturn(List.of(proposal));
        when(support.acceptedCommand(anyLong(), anyLong(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any())).thenReturn(command);
        when(support.derivedKey("validate", "validate-key")).thenReturn("derived-key");
        when(support.now()).thenReturn(NOW);
        when(repository.proposals(tenantId, requestId)).thenReturn(List.of(proposal));
        when(repository.applyValidation(eq(tenantId), eq(actorId), eq(requestId), eq(1L),
                any(), eq(intentId), eq(List.of()), eq(NOW))).thenReturn(true);
        when(audit.command(tenantId, actorId, "VALIDATE_REQUEST", "validate-key"))
                .thenReturn(Optional.empty(), Optional.of(command));
        when(authority.preview(eq(tenantId), eq(actorId), eq(personId), eq("신뢰 사용자"),
                eq("group:trusted"), eq("ko-KR"), eq("derived-key"), eq("corr-23"), any()))
                .thenReturn(preview);

        service.validate(tenantId, actorId, personId, "신뢰 사용자", "group:trusted",
                "ko-KR", requestId, "validate-key", "corr-23",
                new ValidateAssistantRequest(
                        1, SelectionMode.ALL, List.of(), 120, true, "Validate"));

        verify(audit).lockCommand(tenantId, actorId, "VALIDATE_REQUEST", "validate-key");
        verify(authority).preview(eq(tenantId), eq(actorId), eq(personId),
                eq("신뢰 사용자"), eq("group:trusted"), eq("ko-KR"),
                eq("derived-key"), eq("corr-23"), any());
        ArgumentCaptor<RequestedBookingItem> canonical =
                ArgumentCaptor.forClass(RequestedBookingItem.class);
        verify(repository).applyProposalValidation(
                eq(tenantId), eq(requestId), eq(proposalId), canonical.capture(),
                eq(PolicyResult.ALLOWED), anyList(), anyList(), eq(intentItemId), eq(2L),
                eq(resourceId), eq(3L), eq("Desk 1"), eq(NOW));
        assertThat(canonical.getValue().beneficiaryUserId()).isEqualTo(actorId);
        assertThat(canonical.getValue().beneficiaryPersonPublicId()).isEqualTo(personId);
        assertThat(canonical.getValue().beneficiaryDisplayName()).isEqualTo("신뢰 사용자");
        assertThat(canonical.getValue().delegationGrantId()).isNull();
    }
}
