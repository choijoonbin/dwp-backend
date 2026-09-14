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
public class WorkplaceExperienceCollaborationGovernanceService {
    private final WorkplaceSpatialGovernanceService governance;
    private final WorkplaceExperienceCollaborationService collaboration;
    private final ObjectMapper mapper;

    public WorkplaceExperienceCollaborationGovernanceService(WorkplaceSpatialGovernanceService governance,
            WorkplaceExperienceCollaborationService collaboration, ObjectMapper mapper) {
        this.governance = governance;
        this.collaboration = collaboration;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public GovernanceChangeReview reviewRule(long tenantId, long actorId, String groups, UUID siteId,
                                             UUID ruleId, AccessRuleChangeRequest request) {
        SiteAccessRule current = ruleId == null ? null : governance.accessRules(tenantId, siteId).stream()
                .filter(rule -> rule.accessRuleId().equals(ruleId)).findFirst().orElseThrow(this::notFound);
        validateVersion(ruleId, current == null ? null : current.version(), request.proposed().version());
        SiteAccessRuleRequest proposed = request.proposed();
        boolean validSubject = proposed.subjectType() == AccessSubjectType.USER
                ? proposed.subjectUserId() != null && proposed.subjectUserId() > 0 && proposed.subjectGroupRef() == null
                : proposed.subjectType() == AccessSubjectType.GROUP_REF && proposed.subjectUserId() == null && proposed.subjectGroupRef() != null;
        if (!validSubject) throw invalid("A rule requires exactly one user or verified group reference.");
        validatePeriod(proposed.validFrom(), proposed.validUntil());
        if (current != null && !Objects.equals(current.floorId(), proposed.floorId())) throw invalid("An access rule scope is immutable.");
        SiteAccessDecision proposedAccess = governance.previewAccess(tenantId, actorId, groups, siteId, ruleId, proposed);
        SiteAccessDecision currentAccess = governance.evaluateFloorAccess(tenantId, actorId, groups, siteId, proposed.floorId(), proposed.permission());
        return new GovernanceChangeReview("WP_ACCESS_RULE", ruleId, mapper.valueToTree(current), mapper.valueToTree(proposed),
                currentAccess, List.of(proposed.floorId() == null
                        ? "The selected site access rule is the only stored rule changed by this command."
                        : "Only the selected floor rule is saved. Existing site rules remain unchanged.",
                "Existing bookings are not cancelled, moved or recalculated by an access-rule save."),
                List.of("Access comparisons use only the current actor and verified groups at this review time; they do not simulate all employees or future group membership."),
                OffsetDateTime.now(), proposedAccess);
    }

    @Transactional
    public SiteAccessRule changeRule(long tenantId, long actorId, String groups, UUID siteId,
                                     UUID ruleId, AccessRuleChangeRequest request, String correlationId) {
        WorkplaceExperienceCollaborationService.requireConfirmation(request.reason(), request.confirmed());
        governance.lockSiteAccessScope(tenantId, siteId);
        GovernanceChangeReview review = reviewRule(tenantId, actorId, groups, siteId, ruleId, request);
        SiteAccessRule saved = governance.saveAccessRule(tenantId, actorId, siteId, ruleId, correlationId, request.proposed());
        collaboration.audit(tenantId, actorId, "workplace.governance.access.rule.reviewed", "WP_ACCESS_RULE",
                saved.accessRuleId(), correlationId, review.current(), saved, request.reason());
        return saved;
    }

    @Transactional(readOnly = true)
    public GovernanceChangeReview reviewDelegation(long tenantId, UUID delegationId, DelegationChangeRequest request) {
        DelegatedAdminScope current = delegationId == null ? null : governance.delegatedScopes(tenantId).stream()
                .filter(scope -> scope.delegationId().equals(delegationId)).findFirst().orElseThrow(this::notFound);
        validateVersion(delegationId, current == null ? null : current.version(), request.proposed().version());
        DelegatedAdminScopeRequest proposed = request.proposed();
        if (proposed.scopeType() != DelegatedScopeType.SITE || proposed.siteId() == null || proposed.managedGroupRef() != null) {
            throw invalid("Only a registered single-site delegation is supported.");
        }
        governance.validateDelegationReview(tenantId, delegationId, proposed);
        // Existing owner query validates the site and tenant even for a creation review.
        governance.accessRules(tenantId, proposed.siteId());
        boolean validSubject = proposed.delegateType() == DelegateType.USER
                ? proposed.delegateUserId() != null && proposed.delegateUserId() > 0 && proposed.delegateGroupRef() == null
                : proposed.delegateType() == DelegateType.GROUP_REF && proposed.delegateUserId() == null && proposed.delegateGroupRef() != null;
        if (!validSubject || proposed.permissions() == null || proposed.permissions().isEmpty()
                || proposed.permissions().size() != java.util.Set.copyOf(proposed.permissions()).size()) {
            throw invalid("Choose exactly one delegate and unique allowed actions.");
        }
        validatePeriod(proposed.validFrom(), proposed.validUntil());
        return new GovernanceChangeReview("WP_DELEGATION", delegationId, mapper.valueToTree(current), mapper.valueToTree(proposed),
                null, List.of("Only the selected delegation and its listed site/actions/validity are changed.",
                "A delegation does not grant Workplace or Rooms application entitlement."),
                List.of("Saved delegation scope is checked on subsequent administrator requests; no external approval or instant cache-propagation guarantee is asserted."),
                OffsetDateTime.now());
    }

    @Transactional
    public DelegatedAdminScope changeDelegation(long tenantId, long actorId, UUID delegationId,
                                               DelegationChangeRequest request, String correlationId) {
        WorkplaceExperienceCollaborationService.requireConfirmation(request.reason(), request.confirmed());
        GovernanceChangeReview review = reviewDelegation(tenantId, delegationId, request);
        DelegatedAdminScope saved = governance.saveDelegatedScope(tenantId, actorId, delegationId, correlationId, request.proposed());
        collaboration.audit(tenantId, actorId, "workplace.governance.delegation.reviewed", "WP_DELEGATION",
                saved.delegationId(), correlationId, review.current(), saved, request.reason());
        return saved;
    }

    private void validateVersion(UUID targetId, Long currentVersion, Long proposedVersion) {
        if (targetId == null && proposedVersion != null) throw invalid("A new item has no saved version.");
        if (targetId != null && !Objects.equals(currentVersion, proposedVersion)) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, "The original item changed. Refresh its saved version before review or save.");
        }
    }

    private void validatePeriod(OffsetDateTime from, OffsetDateTime until) {
        if (from != null && until != null && !until.isAfter(from)) throw invalid("Validity end must be later than its start.");
    }

    private BaseException notFound() { return new BaseException(ErrorCode.NOT_FOUND, "The selected governance item is not in the current scope."); }
    private BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
}
