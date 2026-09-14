package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceExperienceCollaborationDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;

@Service
public class WorkplaceExperienceCollaborationPolicyGovernanceService {
    private final WorkplaceService workplace;
    private final WorkplaceSpatialGovernanceService governance;
    private final WorkplaceExperienceCollaborationService collaboration;
    private final ObjectMapper mapper;

    public WorkplaceExperienceCollaborationPolicyGovernanceService(WorkplaceService workplace,
            WorkplaceSpatialGovernanceService governance, WorkplaceExperienceCollaborationService collaboration,
            ObjectMapper mapper) {
        this.workplace = workplace;
        this.governance = governance;
        this.collaboration = collaboration;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public GovernanceChangeReview reviewBookingPolicy(long tenantId, BookingPolicyChangeRequest request) {
        WorkplaceDtos.Policy current = workplace.policy(tenantId);
        WorkplaceDtos.PolicyRequest proposed = request.proposed();
        if (!Objects.equals(current.version(), proposed.version())) throw conflict();
        if (!proposed.workingDayEnd().isAfter(proposed.workingDayStart())
                || proposed.maximumBookingMinutes() < proposed.minimumBookingMinutes()
                || proposed.maximumConsecutiveDays() > proposed.bookingWindowDays()) {
            throw invalid("Working hours, duration bounds and consecutive days must form a valid booking policy.");
        }
        return new GovernanceChangeReview("WP_BOOKING_POLICY", null, mapper.valueToTree(current), mapper.valueToTree(proposed),
                null, List.of("The tenant booking policy is updated for subsequent policy evaluations.",
                "Existing booking snapshots remain unchanged; this command does not cancel, move or recalculate bookings.",
                "Retention maintenance uses the saved booking-retention policy while retaining legal-hold bookings."),
                List.of("This review does not predict all future employee behavior, occupancy, SLA or external connector outcomes."),
                OffsetDateTime.now());
    }

    @Transactional
    public WorkplaceDtos.Policy changeBookingPolicy(long tenantId, long actorId, BookingPolicyChangeRequest request,
                                                    String correlationId) {
        WorkplaceExperienceCollaborationService.requireConfirmation(request.reason(), request.confirmed());
        GovernanceChangeReview review = reviewBookingPolicy(tenantId, request);
        WorkplaceDtos.Policy saved = workplace.updatePolicy(tenantId, actorId, correlationId, request.proposed());
        collaboration.audit(tenantId, actorId, "workplace.governance.booking.policy.reviewed", "WP_BOOKING_POLICY", null,
                correlationId, review.current(), saved, request.reason());
        return saved;
    }

    @Transactional(readOnly = true)
    public GovernanceChangeReview reviewPolicyOverride(long tenantId, UUID overrideId, PolicyScopeType scopeType,
                                                       UUID scopeId, PolicyOverrideChangeRequest request) {
        PolicyOverrideRequest proposed = request.proposed();
        if (scopeType != proposed.scopeType() || !Objects.equals(scopeId, proposed.scopeId())) {
            throw invalid("The proposed override must match the requested policy scope.");
        }
        PolicyOverride current = overrideId == null ? null : governance.policyOverrides(tenantId, scopeType, scopeId).stream()
                .filter(value -> value.policyOverrideId().equals(overrideId)).findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND, "The selected override is not in the current scope."));
        if (overrideId == null && proposed.version() != null) throw invalid("A new override has no saved version.");
        if (current != null && !Objects.equals(current.version(), proposed.version())) throw conflict();
        // Read the registered native scope and current effective policy; no simulated outcome is asserted.
        governance.previewPolicy(tenantId, scopeType, scopeId);
        if (proposed.policyPatch() == null || !proposed.policyPatch().isObject() || proposed.policyPatch().isEmpty()) {
            throw invalid("An override requires a non-empty policy patch.");
        }
        return new GovernanceChangeReview("WP_POLICY_OVERRIDE", overrideId, mapper.valueToTree(current), mapper.valueToTree(proposed),
                null, List.of("Only the selected native policy override at this exact scope is saved.",
                "Subsequent effective-policy evaluations combine the saved tenant/site/floor/resource overrides.",
                "Existing booking snapshots remain unchanged."),
                List.of("The review reports current scope and stored values; it does not predict booking counts or simulate every future request."),
                OffsetDateTime.now());
    }

    @Transactional
    public PolicyOverride changePolicyOverride(long tenantId, long actorId, UUID overrideId, PolicyScopeType scopeType,
            UUID scopeId, PolicyOverrideChangeRequest request, String correlationId) {
        WorkplaceExperienceCollaborationService.requireConfirmation(request.reason(), request.confirmed());
        GovernanceChangeReview review = reviewPolicyOverride(tenantId, overrideId, scopeType, scopeId, request);
        PolicyOverride saved = governance.savePolicyOverride(tenantId, actorId, overrideId, correlationId,
                scopeType, scopeId, request.proposed());
        collaboration.audit(tenantId, actorId, "workplace.governance.policy.override.reviewed", "WP_POLICY_OVERRIDE",
                saved.policyOverrideId(), correlationId, review.current(), saved, request.reason());
        return saved;
    }

    private BaseException conflict() { return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, "The saved policy changed. Reload and review the current version."); }
    private BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
}
