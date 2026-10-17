package com.dwp.services.platform.hrisconfiguration.operations;

import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class HrisOperationsModels {

    static final Set<String> FIELD_GROUPS = Set.of(
            "OPERATION_EXCEPTION",
            "OWNER_DEEP_LINK",
            "RECOVERY_METADATA",
            "REDACTED_PAYLOAD");
    static final Set<String> SETTINGS_FIELD_GROUPS = Set.of(
            "OPERATION_POLICY", "EXCEPTION_POLICY", "EFFECTIVE_STATUS");

    private HrisOperationsModels() {
    }

    enum OwnerModule { HRM, PER, PAY, SYS, TIM }

    enum Severity { INFO, WARNING, HIGH, CRITICAL }

    enum InternalState { OPEN, ACKNOWLEDGED, RECOVERING, RESOLVED, SUPPRESSED }

    enum PublicState { OPEN, ACKNOWLEDGED, RESOLVED }

    enum FieldDecision { VIEW, MASK, OMIT }

    enum ProjectionState { COMPLETE, PARTIAL, STALE, UNAVAILABLE }

    enum SourceState { AVAILABLE, STALE, UNAVAILABLE }

    enum IngressState {
        APPLIED,
        DUPLICATE,
        DEFERRED,
        QUARANTINED,
        DEAD_LETTER_REQUIRED,
        IGNORED_NOT_ENROLLED,
        IGNORED_UNRELATED
    }

    record AccessSeal(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String routeContractKey,
            String contextKey,
            String contextScopeKey,
            String decisionRevision,
            long fieldPolicyRevision,
            long authorizationRevision,
            OffsetDateTime freshUntil,
            Set<OwnerModule> ownerModules,
            Map<String, FieldDecision> fieldDecisions) {
        AccessSeal {
            ownerModules = ownerModules == null ? Set.of() : Set.copyOf(ownerModules);
            fieldDecisions = fieldDecisions == null
                    ? Map.of() : Map.copyOf(fieldDecisions);
        }
    }

    record ValidatedEvent(
            DomainEventEnvelope envelope,
            UUID exceptionPublicId,
            String ownerService,
            OwnerModule ownerModule,
            String sourceRef,
            long sourceRevision,
            String exceptionType,
            Severity severity,
            InternalState internalState,
            String code,
            String summaryKey,
            String ownerRouteKey,
            List<String> evidenceRefs,
            UUID correlationId,
            Instant occurredAt,
            String eventDigest,
            String canonicalPayload) {
        ValidatedEvent {
            evidenceRefs = List.copyOf(evidenceRefs);
        }
    }

    record Projection(
            long tenantId,
            UUID exceptionPublicId,
            String eventSource,
            String ownerService,
            OwnerModule ownerModule,
            String sourceRef,
            long sourceRevision,
            String exceptionType,
            Severity severity,
            InternalState internalState,
            String code,
            String summaryKey,
            String ownerRouteKey,
            List<String> evidenceRefs,
            UUID correlationId,
            String eventDigest,
            OffsetDateTime firstObservedAt,
            OffsetDateTime lastObservedAt,
            long occurrenceCount,
            long version) {
        Projection {
            evidenceRefs = List.copyOf(evidenceRefs);
        }
    }

    record ProjectionPosition(OffsetDateTime lastObservedAt, UUID exceptionPublicId) {
    }

    record ProjectionPage(List<Projection> items, ProjectionPosition nextPosition) {
        ProjectionPage {
            items = List.copyOf(items);
        }
    }

    record ProjectionSummary(
            OffsetDateTime asOf,
            OffsetDateTime lastSuccessfulRefreshAt,
            Map<OwnerModule, OffsetDateTime> latestBySource,
            long total,
            long open,
            long acknowledged,
            long resolved,
            long highImpact) {
        ProjectionSummary {
            latestBySource = Map.copyOf(latestBySource);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ExceptionListItem(
            UUID exceptionPublicId,
            OwnerModule ownerModule,
            Severity severity,
            PublicState state,
            String code,
            String summaryKey,
            String ownerRouteKey,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String ownerDeepLink,
            long sourceRevision,
            OffsetDateTime firstObservedAt,
            OffsetDateTime lastObservedAt,
            long occurrenceCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ExceptionListResponse(
            OffsetDateTime asOf,
            List<ExceptionListItem> items,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String nextCursor) {
        ExceptionListResponse {
            items = List.copyOf(items);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ExceptionDetailResponse(
            UUID exceptionPublicId,
            OwnerModule ownerModule,
            Severity severity,
            PublicState state,
            String code,
            String summaryKey,
            String ownerRouteKey,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String ownerDeepLink,
            long sourceRevision,
            OffsetDateTime firstObservedAt,
            OffsetDateTime lastObservedAt,
            long occurrenceCount,
            String type,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            UUID correlationId,
            List<String> evidenceRefs,
            Map<String, FieldDecision> fieldDecisions) {
        ExceptionDetailResponse {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            fieldDecisions = Map.copyOf(fieldDecisions);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SourceStatus(
            OwnerModule ownerModule,
            SourceState state,
            String reasonCode) {
    }

    record ExceptionCounts(
            long total,
            long open,
            long acknowledged,
            long resolved,
            long highImpact) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SettingsOperationsResponse(
            OffsetDateTime asOf,
            ProjectionState projectionState,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            OffsetDateTime lastSuccessfulRefreshAt,
            List<SourceStatus> sources,
            ExceptionCounts counts) {
        SettingsOperationsResponse {
            sources = List.copyOf(sources);
        }
    }

    record IngressResult(IngressState state, int attempt, String reason) {
        static IngressResult of(IngressState state) {
            return new IngressResult(state, 0, null);
        }
    }
}
