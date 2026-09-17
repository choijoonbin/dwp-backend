package com.dwp.services.platform.workplace.workplaceassistant;

import java.util.List;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.RequestedBookingItem;

public interface WorkplaceAssistantSuggestionProvider {
    boolean supports(String providerReference);

    SuggestionResult suggest(SuggestionContext context);

    record SuggestionContext(
            long tenantId,
            long actorUserId,
            String redactedRequestText,
            List<RequestedBookingItem> requestedItems,
            String modelVersion,
            String promptVersion,
            String toolVersion,
            String locale) { }

    record SuggestedItem(
            String clientItemKey,
            String rationale,
            List<String> constraintsUsed,
            List<String> exclusions) { }

    record SuggestionResult(
            List<SuggestedItem> items,
            List<String> limitations) { }
}
