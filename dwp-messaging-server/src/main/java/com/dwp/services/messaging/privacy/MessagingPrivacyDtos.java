package com.dwp.services.messaging.privacy;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class MessagingPrivacyDtos {
    private MessagingPrivacyDtos() { }

    public record PrivacyPreference(boolean readReceiptsEnabled, long version) { }

    public record UpdatePrivacyPreferenceRequest(
            @NotNull Boolean readReceiptsEnabled,
            @NotNull @Min(0) Long version) { }
}
