package com.dwp.services.platform.workplace.workplaceassistant;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.RequestedBookingItem;

@Component
public final class StructuredWorkplaceAssistantSuggestionProvider
        implements WorkplaceAssistantSuggestionProvider {
    public static final String PROVIDER_REFERENCE = "DWP_STRUCTURED_ASSISTANT";

    @Override
    public boolean supports(String providerReference) {
        return PROVIDER_REFERENCE.equals(providerReference);
    }

    @Override
    public SuggestionResult suggest(SuggestionContext context) {
        List<SuggestedItem> suggestions = new ArrayList<>();
        for (RequestedBookingItem item : context.requestedItems()) {
            List<String> constraints = new ArrayList<>();
            constraints.add("DATE_TIME");
            constraints.add("RESOURCE_TYPE:" + item.resourceType().name());
            if (item.siteId() != null) constraints.add("SITE");
            if (item.floorId() != null) constraints.add("FLOOR");
            if (item.accessibleOnly()) constraints.add("ACCESSIBLE_ONLY");
            if (!item.requiredFeatures().isEmpty()) constraints.add("REQUIRED_FEATURES");
            if (item.delegationGrantId() != null) constraints.add("DELEGATED_BENEFICIARY");
            suggestions.add(new SuggestedItem(
                    item.clientItemKey(),
                    "A reviewable option was prepared from the dates, resource type, "
                            + "beneficiary and constraints supplied by the user.",
                    List.copyOf(constraints),
                    List.of("Availability, access and booking policy remain unverified until "
                            + "the authoritative validation step.")));
        }
        return new SuggestionResult(List.copyOf(suggestions), List.of(
                "This provider structures user-supplied constraints; it does not assert "
                        + "availability, permission, policy or booking success."));
    }
}
