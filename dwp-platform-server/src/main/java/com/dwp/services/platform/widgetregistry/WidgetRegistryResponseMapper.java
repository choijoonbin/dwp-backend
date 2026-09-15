package com.dwp.services.platform.widgetregistry;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class WidgetRegistryResponseMapper {
    private final WidgetRendererBindingRepository bindings;

    WidgetRegistryResponseMapper(WidgetRendererBindingRepository bindings) {
        this.bindings = bindings;
    }

    WidgetRegistryDtos.DefinitionResponse definition(WidgetDefinition value) {
        return new WidgetRegistryDtos.DefinitionResponse(
                value.getDefinitionId(), value.getDefinitionKey(), value.getLegacyWidgetKey(),
                value.getOwnerProductKey(), value.getOwnerTeamKey(), value.getRiskTier(),
                value.getDataClassification(), value.getDefinitionState(), version(value.getVersion()),
                value.getCreatedAt(), value.getUpdatedAt(), definitionTransitions(value));
    }

    WidgetRegistryDtos.VersionResponse version(WidgetDefinitionVersion value) {
        String bindingRevision = bindings
                .findByRendererKeyAndBindingState(value.getRendererKey(), "ACTIVE")
                .map(WidgetRendererBinding::getBindingRevision).orElse(null);
        return new WidgetRegistryDtos.VersionResponse(
                value.getVersionId(), value.getDefinitionId(), value.getSemanticVersion(),
                value.getManifest(), value.getManifestHash(), value.getWorkflowState(),
                value.getReleaseState(), value.getSafetyState(), value.getAttestation(),
                value.getCertificationStatus(), value.getPredecessorVersionId(),
                value.getReplacementVersionId(), value.getValidationRunId(), bindingRevision,
                version(value.getVersion()), value.getCreatedAt(), value.getUpdatedAt(),
                versionTransitions(value));
    }

    WidgetRegistryDtos.EvidenceResponse evidence(WidgetEvidence value) {
        return new WidgetRegistryDtos.EvidenceResponse(
                value.getEvidenceId(), value.getVersionId(), value.getEvidenceType(),
                value.getEvidenceStatus(), value.getManifestHash(), value.getEvidenceRef(),
                value.getEvidenceSha256(), value.getExpiresAt(), value.getDecisionRevision(),
                value.getWaivedEvidenceId(), value.getTrackingTicketRef(),
                "actor:" + value.getReviewedBy(), value.getCreatedAt());
    }

    WidgetRegistryDtos.ReleaseChannelResponse channel(WidgetReleaseChannel value) {
        return new WidgetRegistryDtos.ReleaseChannelResponse(
                value.getDefinitionId(), value.getChannel(), value.getCurrentVersionId(),
                value.getPreviousVersionId(), version(value.getVersion()), value.getUpdatedAt(),
                List.of("PROMOTE", "ROLLBACK"));
    }

    WidgetRegistryDtos.TenantPolicyRevisionResponse policyRevision(
            TenantWidgetPolicyRevision value) {
        return new WidgetRegistryDtos.TenantPolicyRevisionResponse(
                value.getPolicyRevisionId(), value.getTenantId(), value.getDefinitionId(),
                value.getRevisionNumber(), value.getPolicyState(), value.isEnabled(),
                value.getSelectorType(), value.getChannel(), value.getVersionId(),
                value.getSupportedSurfaceKeys(), value.getAudienceSelector(),
                value.isRequiredWidget(), value.getLockedConfiguration(), value.getSharingPolicy(),
                value.getImpactRevision(), value.getPredecessorRevisionId(),
                version(value.getVersion()), value.getCreatedAt());
    }

    WidgetRegistryDtos.RuntimeControlResponse control(WidgetRuntimeControl value) {
        return new WidgetRegistryDtos.RuntimeControlResponse(
                value.getControlId(), value.getTenantId(), value.getProviderProductKey(),
                value.getControlScope(), value.getTargetType(), value.getTargetId(),
                value.getControlState(), value.getControlRevision(), value.getReasonCode(),
                value.getExpiresAt(), version(value.getVersion()), value.getCreatedAt());
    }

    private List<String> definitionTransitions(WidgetDefinition value) {
        return "ACTIVE".equals(value.getDefinitionState()) ? List.of("RETIRE") : List.of();
    }

    private List<String> versionTransitions(WidgetDefinitionVersion value) {
        if ("REVOKED".equals(value.getSafetyState())) return List.of();
        if ("QUARANTINED".equals(value.getSafetyState())) return List.of("REVOKE");
        List<String> values = new ArrayList<>();
        switch (value.getWorkflowState()) {
            case "DRAFT" -> values.add("VALIDATE");
            case "VALIDATED" -> values.add("SUBMIT");
            case "SUBMITTED" -> values.addAll(List.of("APPROVE", "REJECT"));
            case "REJECTED" -> values.add("REWORK");
            case "APPROVED" -> {
                if ("UNPUBLISHED".equals(value.getReleaseState())) values.add("PUBLISH");
            }
            default -> { }
        }
        if ("PUBLISHED".equals(value.getReleaseState())) {
            values.addAll(List.of("BLOCK", "DEPRECATE", "QUARANTINE", "REVOKE"));
        } else if ("BLOCKED".equals(value.getReleaseState())) {
            values.addAll(List.of("QUARANTINE", "REVOKE"));
        } else if ("DEPRECATED".equals(value.getReleaseState())) {
            values.addAll(List.of("BLOCK", "QUARANTINE", "REVOKE"));
        }
        return List.copyOf(values);
    }

    private static long version(Long value) {
        return value == null ? 0 : value;
    }
}
