package com.dwp.services.messaging.appearance;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

public final class MessagingDisplayDtos {

    private MessagingDisplayDtos() {
    }

    public record AppearancePolicy(
            List<String> allowedThemes,
            boolean allowPersonalBackgrounds,
            boolean allowThemeSharing,
            long version) {
    }

    public record DisplayPreference(
            String layoutMode,
            String density,
            String theme,
            boolean showAvatars,
            String timestampMode,
            boolean messagePreview,
            long version,
            AppearancePolicy policy) {
    }

    public record UpdateDisplayPreferenceRequest(
            @NotBlank String layoutMode,
            @NotBlank String density,
            @NotBlank String theme,
            boolean showAvatars,
            @NotBlank String timestampMode,
            boolean messagePreview,
            @Min(0) long version) {
    }

    public record ConversationDisplayPreference(
            UUID conversationId,
            String layoutMode,
            String density,
            String theme,
            String effectiveLayoutMode,
            String effectiveDensity,
            String effectiveTheme,
            boolean showAvatars,
            String timestampMode,
            boolean messagePreview,
            boolean policyLocked,
            String policyReason,
            long version) {
    }

    public record UpdateConversationDisplayPreferenceRequest(
            @NotBlank String layoutMode,
            @NotBlank String density,
            @NotBlank String theme,
            @Min(0) long version) {
    }
}
