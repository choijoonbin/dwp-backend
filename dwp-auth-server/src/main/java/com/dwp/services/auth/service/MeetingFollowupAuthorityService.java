package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.MeetingFollowupAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/** Resolves only the Meeting capability needed for one exact follow-up source action. */
@Service
public class MeetingFollowupAuthorityService {

    static final String PRODUCT_KEY = "meetings";
    static final String SURFACE_KEY = "meetings.work";
    private static final String READ_CAPABILITY = "meetings.work.meetings.read";
    private static final String READ_PERMISSION = "APP.MEETINGS:VIEW";
    private static final String CREATE_CAPABILITY = "meetings.work.meeting.create";
    private static final String CREATE_PERMISSION = "APP.MEETINGS:CREATE";
    private static final List<String> REGISTRY_NOT_READY_REASONS = List.of(
            "PRODUCT_NOT_REGISTERED", "SURFACE_NOT_REGISTERED");

    private final ProductSurfaceAuthorityService authority;
    private final Clock clock;

    @Autowired
    public MeetingFollowupAuthorityService(ProductSurfaceAuthorityService authority) {
        this(authority, Clock.systemUTC());
    }

    MeetingFollowupAuthorityService(ProductSurfaceAuthorityService authority, Clock clock) {
        this.authority = authority;
        this.clock = clock;
    }

    public MeetingFollowupAuthorityDtos.AuthorityResult evaluate(
            MeetingFollowupAuthorityDtos.EvaluateRequest request) {
        if (!valid(request)) {
            return MeetingFollowupAuthorityDtos.AuthorityResult.denied(
                    request, MeetingFollowupAuthorityDtos.Denial.ACTION_NOT_AUTHORIZED);
        }
        if (request.action() == MeetingFollowupAuthorityDtos.Action.REASSIGN) {
            // Reassignment remains closed until People also verifies current target eligibility.
            return MeetingFollowupAuthorityDtos.AuthorityResult.denied(
                    request, MeetingFollowupAuthorityDtos.Denial.ACTION_NOT_AUTHORIZED);
        }
        ProductSurfaceAuthorityDtos.AuthorityResult result;
        try {
            result = authority.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(
                    request.tenantId(), request.actorUserId(), PRODUCT_KEY, SURFACE_KEY,
                    ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                    null, null, null, null, null, List.of()));
        } catch (RuntimeException exception) {
            return MeetingFollowupAuthorityDtos.AuthorityResult.denied(
                    request, MeetingFollowupAuthorityDtos.Denial.AUTHORITY_UNVERIFIED);
        }
        MeetingFollowupAuthorityDtos.Denial denial = denial(result);
        if (denial != null) {
            return MeetingFollowupAuthorityDtos.AuthorityResult.denied(request, denial);
        }
        String capability = request.action() == MeetingFollowupAuthorityDtos.Action.CREATE
                ? CREATE_CAPABILITY : READ_CAPABILITY;
        String permission = request.action() == MeetingFollowupAuthorityDtos.Action.CREATE
                ? CREATE_PERMISSION : READ_PERMISSION;
        boolean grantPresent = result.effectiveGrants().stream()
                .filter(ProductSurfaceAuthorityDtos.CapabilityGrant.class::isInstance)
                .map(ProductSurfaceAuthorityDtos.CapabilityGrant.class::cast)
                .anyMatch(grant -> capability.equals(grant.capabilityContractKey())
                        && permission.equals(grant.resolvedCapabilityCode())
                        && grant.activationState()
                                == ProductSurfaceAuthorityDtos.ActivationState.ACTIVE
                        && (request.action() != MeetingFollowupAuthorityDtos.Action.CREATE
                                || !grant.readOnly()));
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean freshEvidence = result.revalidateAt() != null
                && result.revalidateAt().isAfter(now)
                && result.authRevision() != null && !result.authRevision().isBlank()
                && result.policyRevision() != null && !result.policyRevision().isBlank()
                && result.evidenceRef() != null && !result.evidenceRef().isBlank();
        if (!grantPresent) {
            return MeetingFollowupAuthorityDtos.AuthorityResult.denied(
                    request, MeetingFollowupAuthorityDtos.Denial.ACTION_NOT_AUTHORIZED);
        }
        if (!freshEvidence) {
            return MeetingFollowupAuthorityDtos.AuthorityResult.denied(
                    request, MeetingFollowupAuthorityDtos.Denial.AUTHORITY_UNVERIFIED);
        }
        return MeetingFollowupAuthorityDtos.AuthorityResult.allowed(request, result);
    }

    private MeetingFollowupAuthorityDtos.Denial denial(
            ProductSurfaceAuthorityDtos.AuthorityResult result) {
        if (result == null || result.decision() == null) {
            return MeetingFollowupAuthorityDtos.Denial.AUTHORITY_UNVERIFIED;
        }
        if (result.decision() != ProductSurfaceAuthorityDtos.Decision.ALLOWED) {
            if (result.decision() == ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED
                    && REGISTRY_NOT_READY_REASONS.contains(result.reasonCode())) {
                return MeetingFollowupAuthorityDtos.Denial.AUTHORITY_UNVERIFIED;
            }
            return switch (result.decision()) {
                case AUTHORITY_UNAVAILABLE ->
                        MeetingFollowupAuthorityDtos.Denial.AUTHORITY_UNVERIFIED;
                case SCOPE_SELECTION_REQUIRED, SCOPE_INVALID, SUPPORT_SCOPE_DENIED ->
                        MeetingFollowupAuthorityDtos.Denial.SCOPE_FORBIDDEN;
                case STEP_UP_REQUIRED, SOD_CONFLICT ->
                        MeetingFollowupAuthorityDtos.Denial.ACTION_NOT_AUTHORIZED;
                default -> MeetingFollowupAuthorityDtos.Denial.AUTHORITY_REVOKED;
            };
        }
        if (!PRODUCT_KEY.equals(result.productKey()) || !SURFACE_KEY.equals(result.surfaceKey())
                || !"work".equals(result.plane())
                || result.accessMode() != ProductSurfaceAuthorityDtos.AccessMode.NORMAL) {
            return MeetingFollowupAuthorityDtos.Denial.IDENTITY_PLANE_MISMATCH;
        }
        return null;
    }

    private boolean valid(MeetingFollowupAuthorityDtos.EvaluateRequest request) {
        if (request == null || request.tenantId() <= 0 || request.actorUserId() <= 0
                || request.source() == null || request.source().meetingId() == null
                || request.source().reportId() == null || request.source().candidateId() == null
                || request.action() == null) {
            return false;
        }
        return switch (request.action()) {
            case READ -> request.targetAssigneeUserId() == null
                    && request.expectedSourceVersion() == null;
            case CREATE -> request.targetAssigneeUserId() == null
                    && request.expectedSourceVersion() != null
                    && request.expectedSourceVersion() >= 0;
            case REASSIGN -> request.targetAssigneeUserId() != null
                    && request.targetAssigneeUserId() > 0
                    && request.expectedSourceVersion() == null;
        };
    }
}
