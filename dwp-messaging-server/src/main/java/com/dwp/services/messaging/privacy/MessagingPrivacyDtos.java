package com.dwp.services.messaging.privacy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class MessagingPrivacyDtos {
    private MessagingPrivacyDtos() { }

    public record PrivacyPreference(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean readReceiptsEnabled,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean presenceSharingEnabled,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long version) {
        public PrivacyPreference(boolean readReceiptsEnabled, long version) {
            this(readReceiptsEnabled, true, version);
        }
    }

    public record UpdatePrivacyPreferenceRequest(
            @Schema(nullable = true, description = "Optional read-status sharing choice; omitted values are preserved")
            Boolean readReceiptsEnabled,
            @Schema(nullable = true, description = "Optional ephemeral presence sharing choice; omitted values are preserved")
            Boolean presenceSharingEnabled,
            @NotNull @Min(0) Long version) {
        public UpdatePrivacyPreferenceRequest(Boolean readReceiptsEnabled, Long version) {
            this(readReceiptsEnabled, null, version);
        }

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = "At least one privacy sharing choice is required")
        public boolean isSharingChoiceProvided() {
            return readReceiptsEnabled != null || presenceSharingEnabled != null;
        }
    }
}
