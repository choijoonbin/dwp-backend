package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.DecimalVersionStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NotificationTemplateModels {

    private NotificationTemplateModels() {
    }

    public record TemplateContent(
            String title,
            String preview,
            String body,
            String actionLabel) {
    }

    public record TemplateRevision(
            UUID revisionId,
            UUID typeVersionId,
            String typeKey,
            String appKey,
            String channel,
            String locale,
            String state,
            int revision,
            TemplateContent content,
            String checksum,
            String changeReason,
            Long createdBy,
            Long approvedBy,
            Instant approvedAt,
            String approvalReason,
            String version,
            Instant createdAt) {
    }

    public record TemplateVariant(
            UUID typeVersionId,
            String typeKey,
            String displayName,
            String appKey,
            String appName,
            String channel,
            String locale,
            List<String> allowedVariables,
            String version,
            TemplateContent providerDefault,
            TemplateRevision publishedOverride,
            TemplateRevision draft,
            List<TemplateRevision> history) {
    }

    public record TemplateWorkspace(
            List<TemplateVariant> items,
            Instant generatedAt) {
    }

    public record TemplatePreviewRequest(
            @NotNull UUID typeVersionId,
            @NotBlank @Pattern(regexp = "IN_APP|EMAIL|WEB_PUSH|MOBILE_PUSH|TEAMS|SLACK")
            String channel,
            @NotBlank @Size(max = 35) String locale,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 600) String preview,
            @NotBlank @Size(max = 4000) String body,
            @Size(max = 100) String actionLabel,
            @NotNull @Size(max = 30) Map<String, String> sampleData) {
    }

    public record TemplateDraftRequest(
            @NotNull UUID typeVersionId,
            @NotBlank @Pattern(regexp = "IN_APP|EMAIL|WEB_PUSH|MOBILE_PUSH|TEAMS|SLACK")
            String channel,
            @NotBlank @Size(max = 35) String locale,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 600) String preview,
            @NotBlank @Size(max = 4000) String body,
            @Size(max = 100) String actionLabel,
            @NotBlank @Size(min = 10, max = 500) String changeReason,
            @NotBlank
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion) {
    }

    public record TemplatePreview(
            TemplateContent rendered,
            List<String> variables,
            List<String> warnings) {
    }

    public record TemplateDecisionRequest(
            @NotBlank
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion,
            @NotBlank @Size(min = 10, max = 500) String reason) {
    }
}
