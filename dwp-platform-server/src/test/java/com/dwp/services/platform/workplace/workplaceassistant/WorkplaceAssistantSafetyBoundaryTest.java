package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.GovernanceRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.RequestRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantSuggestionProvider.SuggestedItem;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantSuggestionProvider.SuggestionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkplaceAssistantSafetyBoundaryTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T00:00:00Z");

    @Test
    void idempotencyAndCorrelationIdentifiersRequireBoundedVisibleAscii() {
        assertThat(WorkplaceAssistantSupport.requireKey("request-23.A_B:c/1"))
                .isEqualTo("request-23.A_B:c/1");
        assertThat(WorkplaceAssistantSupport.correlation(null)).isNotBlank();
        for (String invalid : List.of("", " leading", "trailing ", "line\nbreak",
                "carriage\rreturn", "tab\tvalue", "한글", "x".repeat(161))) {
            assertThatThrownBy(() -> WorkplaceAssistantSupport.requireKey(invalid))
                    .isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> WorkplaceAssistantSupport.correlation(invalid))
                    .isInstanceOf(BaseException.class);
        }
    }

    @Test
    void providerOutputIsBoundedAndRedactedBeforeStorage() {
        WorkplaceAssistantSupport support = new WorkplaceAssistantSupport(
                mock(WorkplaceAssistantRepository.class), List.of(),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        RequestedBookingItem requested = requestedItem();
        var validated = support.validateSuggestion(List.of(requested), new SuggestionResult(
                List.of(new SuggestedItem("item-1", "Contact user@example.com",
                        List.of("api_key=abcd1234"), List.of("Bearer abcdefghijkl"))),
                List.of("Call 010-1234-5678 only after consent")),
                new WorkplaceAssistantRedactor());

        SuggestedItem stored = validated.itemsByKey().get("item-1");
        assertThat(stored.rationale()).doesNotContain("user@example.com");
        assertThat(stored.constraintsUsed().getFirst()).doesNotContain("abcd1234");
        assertThat(stored.exclusions().getFirst()).doesNotContain("abcdefghijkl");
        assertThat(validated.limitations().getFirst()).doesNotContain("010-1234-5678");

        assertThatThrownBy(() -> support.validateSuggestion(List.of(requested),
                new SuggestionResult(List.of(new SuggestedItem(
                        "item-1", "x".repeat(1001), List.of(), List.of())), List.of()),
                new WorkplaceAssistantRedactor())).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> support.validateSuggestion(List.of(requested),
                new SuggestionResult(List.of(new SuggestedItem(
                        "item-1", "unsafe\ntext", List.of(), List.of())), List.of()),
                new WorkplaceAssistantRedactor())).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> support.validateSuggestion(List.of(requested),
                new SuggestionResult(List.of(new SuggestedItem(
                        "item-1", "safe", List.of(), List.of())),
                        java.util.stream.IntStream.range(0, 51)
                                .mapToObj(value -> "limit-" + value).toList()),
                new WorkplaceAssistantRedactor())).isInstanceOf(BaseException.class);
    }

    @Test
    void feedbackReuseRequiresOperationalGovernanceAndEveryConsent() {
        RequestRow current = mock(RequestRow.class);
        when(current.requestProcessingConsent()).thenReturn(true);
        when(current.feedbackUseConsent()).thenReturn(true);
        FeedbackRequest feedback = new FeedbackRequest(
                1, FeedbackRating.HELPFUL, "Useful", true, true, "Improve model");

        assertThat(WorkplaceAssistantGovernanceService.eligibleForModelImprovement(
                governance(true, false), current, feedback)).isTrue();
        assertThat(WorkplaceAssistantGovernanceService.eligibleForModelImprovement(
                governance(false, false), current, feedback)).isFalse();
        assertThat(WorkplaceAssistantGovernanceService.eligibleForModelImprovement(
                governance(true, true), current, feedback)).isFalse();
    }

    private static RequestedBookingItem requestedItem() {
        return new RequestedBookingItem(
                "item-1", 101L, UUID.randomUUID(), "Trusted user", null,
                ResourceType.DESK, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusDays(1), NOW.plusDays(1).plusHours(1), "Focus", true, false,
                List.of());
    }

    private static GovernanceRow governance(boolean optedIn, boolean killSwitch) {
        return new GovernanceRow(
                42L, optedIn, killSwitch,
                StructuredWorkplaceAssistantSuggestionProvider.PROVIDER_REFERENCE,
                "model-1", "prompt-1", "tool-1", 30, true,
                GovernanceRedactionState.READY, 1, NOW, 101L);
    }
}
