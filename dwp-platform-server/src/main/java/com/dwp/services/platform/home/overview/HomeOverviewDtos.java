package com.dwp.services.platform.home.overview;

import com.dwp.services.platform.calendar.CalendarDtos;
import com.dwp.services.platform.communication.CommunicationDtos;
import com.dwp.services.platform.workspace.WorkspaceDtos;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class HomeOverviewDtos {

    private HomeOverviewDtos() {
    }

    public enum SectionStatus {
        AVAILABLE,
        FORBIDDEN,
        UNAVAILABLE
    }

    public record Section<T>(
            SectionStatus status,
            String source,
            OffsetDateTime generatedAt,
            T data,
            String reason) {
    }

    public record AudienceContext(
            String profile,
            String ruleVersion,
            List<String> reasons) {
    }

    public record Recommendation(
            String key,
            String kind,
            String priority,
            String title,
            String description,
            String actionPath,
            String source,
            int evidenceCount,
            String confidence) {
    }

    public record RecommendationFeedbackRequest(
            @NotBlank
            @Pattern(regexp = "HELPFUL|NOT_RELEVANT|DISMISSED")
            String feedbackType) {
    }

    public record RecommendationFeedbackResponse(
            String recommendationKey,
            String feedbackType,
            String ruleVersion,
            OffsetDateTime recordedAt) {
    }

    public record HomeOverviewResponse(
            AudienceContext audience,
            Section<WorkspaceDtos.WorkQueue> work,
            Section<CalendarDtos.HomeResponse> calendar,
            Section<CommunicationDtos.FeedResponse> communications,
            Section<WorkspaceDtos.ActivityFeed> activity,
            List<Recommendation> recommendations,
            OffsetDateTime generatedAt) {
    }
}
