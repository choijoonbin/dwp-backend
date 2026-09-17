package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.DecimalVersionStringDeserializer;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NotificationAttentionModels {

    private NotificationAttentionModels() {
    }

    public record AttentionRule(
            UUID ruleId,
            String scopeKind,
            String scopeKey,
            String displayLabel,
            String effect,
            Map<String, Boolean> channels,
            Instant startsAt,
            Instant expiresAt,
            String source,
            boolean managed,
            boolean exceptionAllowed,
            boolean enabled,
            String version,
            Instant createdAt,
            Instant updatedAt) {

        public AttentionRule {
            channels = channels == null ? Map.of() : Map.copyOf(channels);
        }
    }

    public record AttentionRuleCollection(
            List<AttentionRule> items,
            int maxActiveRules) {

        public AttentionRuleCollection {
            items = List.copyOf(items);
            if (maxActiveRules < 1) {
                throw new IllegalArgumentException("The attention rule limit must be positive.");
            }
        }
    }

    public record AttentionContextDiscovery(
            List<AttentionContextOption> items,
            int limit,
            Instant generatedAt) {

        public AttentionContextDiscovery {
            items = List.copyOf(items);
            if (limit < 1 || limit > 50) {
                throw new IllegalArgumentException(
                        "The attention context discovery limit must be between 1 and 50.");
            }
            if (generatedAt == null) {
                throw new IllegalArgumentException(
                        "The attention context discovery timestamp is required.");
            }
        }
    }

    public record AttentionContextOption(
            String scopeKind,
            String contextKind,
            String scopeKey,
            String displayLabel,
            Instant lastSeenAt) {

        public AttentionContextOption {
            boolean resource = "RESOURCE".equals(scopeKind)
                    && ("PROJECT".equals(contextKind) || "WORK_ITEM".equals(contextKind));
            boolean topic = "TOPIC_TOKEN".equals(scopeKind) && "TOPIC".equals(contextKind);
            if (!resource && !topic) {
                throw new IllegalArgumentException(
                        "The attention context kind does not match its scope kind.");
            }
            if (scopeKey == null || scopeKey.isBlank()
                    || displayLabel == null || displayLabel.isBlank()
                    || lastSeenAt == null) {
                throw new IllegalArgumentException(
                        "Attention context discovery fields are required.");
            }
        }
    }

    public record AttentionRuleCreateRequest(
            @NotBlank @Pattern(regexp = "APP_TYPE|ACTOR|THREAD|RESOURCE|TOPIC_TOKEN")
            String scopeKind,
            @NotBlank @Size(max = 300) String scopeKey,
            @Size(max = 160) String displayLabel,
            @NotBlank @Pattern(regexp = "FOLLOW|PRIORITIZE|MUTE") String effect,
            @JsonSetter(nulls = Nulls.AS_EMPTY, contentNulls = Nulls.FAIL)
            @Size(max = 6) Map<String, Boolean> channels,
            Instant startsAt,
            @Future Instant expiresAt,
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion,
            Boolean enabled) {

        public AttentionRuleCreateRequest {
            channels = channels == null ? Map.of() : Map.copyOf(channels);
        }
    }

    public record AttentionRuleUpdateRequest(
            @NotBlank @Pattern(regexp = "APP_TYPE|ACTOR|THREAD|RESOURCE|TOPIC_TOKEN")
            String scopeKind,
            @NotBlank @Size(max = 300) String scopeKey,
            @Size(max = 160) String displayLabel,
            @NotBlank @Pattern(regexp = "FOLLOW|PRIORITIZE|MUTE") String effect,
            @JsonSetter(nulls = Nulls.AS_EMPTY, contentNulls = Nulls.FAIL)
            @Size(max = 6) Map<String, Boolean> channels,
            Instant startsAt,
            @Future Instant expiresAt,
            @NotBlank
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion,
            Boolean enabled) {

        public AttentionRuleUpdateRequest {
            channels = channels == null ? Map.of() : Map.copyOf(channels);
        }
    }

    public record AttentionRulePreviewRequest(
            @NotBlank @Pattern(regexp = "APP_TYPE|ACTOR|THREAD|RESOURCE|TOPIC_TOKEN")
            String scopeKind,
            @NotBlank @Size(max = 300) String scopeKey,
            @Size(max = 160) String displayLabel,
            @NotBlank @Pattern(regexp = "FOLLOW|PRIORITIZE|MUTE") String effect,
            @JsonSetter(nulls = Nulls.AS_EMPTY, contentNulls = Nulls.FAIL)
            @Size(max = 6) Map<String, Boolean> channels,
            Instant startsAt,
            @Future Instant expiresAt,
            Boolean enabled) {

        public AttentionRulePreviewRequest {
            channels = channels == null ? Map.of() : Map.copyOf(channels);
        }
    }

    public record AttentionRulePreview(
            boolean allowed,
            String effectiveEffect,
            boolean mandatoryConflict,
            String conflictReason,
            Long estimatedAffectedCount,
            boolean estimateAvailable,
            Instant asOf) {
    }

    public record AttentionControls(
            boolean partial,
            List<String> unavailableSources,
            String message,
            UUID notificationId,
            String whyReceived,
            List<AttentionControl> controls,
            Instant generatedAt) {

        public AttentionControls {
            unavailableSources = List.copyOf(unavailableSources);
            controls = List.copyOf(controls);
        }
    }

    public record AttentionControl(
            String controlKey,
            String scopeKind,
            String label,
            String description,
            List<String> allowedEffects,
            String currentEffect,
            boolean policyLocked,
            String policyReason,
            boolean dndBypassAllowed,
            Instant expiresAt,
            UUID ruleId,
            String ruleVersion) {

        public AttentionControl {
            allowedEffects = List.copyOf(allowedEffects);
        }
    }

    public record AttentionControlMutationRequest(
            @NotBlank
            @Pattern(regexp = "MUTE_TYPE|MUTE_CONTEXT|FOLLOW_CONTEXT|PRIORITIZE_ACTOR")
            String controlKey,
            @NotBlank @Pattern(regexp = "FOLLOW|PRIORITIZE|MUTE") String effect,
            @Future Instant expiresAt,
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String previewFingerprint) {

        public AttentionControlMutationRequest(
                String controlKey,
                String effect,
                Instant expiresAt,
                String expectedVersion) {
            this(controlKey, effect, expiresAt, expectedVersion, null);
        }
    }

    public record AttentionControlPreviewRequest(
            @NotBlank
            @Pattern(regexp = "MUTE_TYPE|MUTE_CONTEXT|FOLLOW_CONTEXT|PRIORITIZE_ACTOR")
            String controlKey,
            @NotBlank @Pattern(regexp = "FOLLOW|PRIORITIZE|MUTE") String effect,
            @Future Instant expiresAt) {
    }

    public record AttentionControlImpactPreview(
            String controlKey,
            boolean allowed,
            boolean policyLocked,
            String effectiveEffect,
            String policySource,
            String policyReason,
            String previewFingerprint,
            String currentRuleVersion,
            Instant expiresAt,
            Instant asOf) {

        public AttentionControlImpactPreview {
            if (controlKey == null || controlKey.isBlank()
                    || effectiveEffect == null || effectiveEffect.isBlank()
                    || previewFingerprint == null
                    || !previewFingerprint.matches("[a-f0-9]{64}")
                    || asOf == null
                    || (allowed && policyLocked)) {
                throw new IllegalArgumentException(
                        "The attention control preview is invalid.");
            }
        }
    }

    record AttentionRuleDeletion(UUID ruleId, long deletedVersion) {
    }

    record AttentionScope(String kind, String key, String hash) {
    }

    record AttentionTypeTarget(String appKey, String typeKey) {
    }

    record AttentionScopeEvidence(
            List<AttentionTypeTarget> targets,
            long affectedNotifications) {

        AttentionScopeEvidence {
            targets = List.copyOf(targets);
        }
    }

    record AttentionPolicyDecision(
            boolean locked,
            String policySource,
            String reasonCode,
            String policyRevision) {

        static AttentionPolicyDecision allowed() {
            return new AttentionPolicyDecision(false, null, null, null);
        }
    }

    record NotificationAttentionContext(
            UUID notificationId,
            String appKey,
            String typeKey,
            String reasonCode,
            List<AttentionContextReference> references) {

        NotificationAttentionContext {
            references = List.copyOf(references);
        }
    }

    record AttentionContextReference(
            String scopeKind,
            String scopeKey,
            String displayHint) {
    }
}
