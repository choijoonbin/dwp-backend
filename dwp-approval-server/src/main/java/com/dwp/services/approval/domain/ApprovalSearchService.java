package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

@Service
public class ApprovalSearchService {
    private final ApprovalSearchRepository repository;
    private final ApprovalWorkAuthority authority;
    private final ApprovalIdentityDirectory identities;
    private final ObjectMapper mapper;

    public ApprovalSearchService(ApprovalSearchRepository repository, ApprovalWorkAuthority authority,
                                  ApprovalIdentityDirectory identities, ObjectMapper mapper) {
        this.repository = repository;
        this.authority = authority;
        this.identities = identities;
        this.mapper = mapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApprovalWorkDtos.Page<ApprovalDtos.TaskSummary> tasks(ApprovalWorkDtos.TaskView view, ApprovalWorkDtos.SearchFilter filter) {
        validate(filter);
        var actor = authority.require("ACTION.APPROVAL_TASK:VIEW", false);
        repository.requireTenant(actor);
        var delegation = currentDelegation(actor);
        var result = repository.tasks(actor, view, filter, delegation.users(), delegation.roles());
        var latest = authority.require("ACTION.APPROVAL_TASK:VIEW", false);
        if (!actor.roles().equals(latest.roles()) || !delegation.equals(currentDelegation(latest))) throw unavailable();
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApprovalWorkDtos.Page<ApprovalDtos.RequestSummary> requests(ApprovalWorkDtos.RequestView view, ApprovalWorkDtos.SearchFilter filter) {
        validate(filter);
        var actor = authority.require("ACTION.APPROVAL_REQUEST:VIEW", true);
        repository.requireTenant(actor);
        var result = repository.requests(actor, view, filter);
        authority.require("ACTION.APPROVAL_REQUEST:VIEW", true);
        return result;
    }

    private Delegation currentDelegation(ApprovalRequestContext.Actor actor) {
        var users = new ArrayList<Long>();
        var roles = new TreeMap<String, List<String>>();
        for (var delegator : repository.delegators(actor)) {
            var subject = identities.require(actor.tenantId(), delegator.userId());
            if (subject == null) throw unavailable();
            if (!subject.active() || !actor.tenantId().equals(subject.tenantId())
                    || !Long.valueOf(delegator.userId()).equals(subject.userId())) continue;
            users.add(delegator.userId());
            roles.put(Long.toString(delegator.userId()), subject.roles() == null ? List.of() : subject.roles().stream().sorted().toList());
        }
        users.sort(Long::compareTo);
        try { return new Delegation(List.copyOf(users), mapper.writeValueAsString(roles)); }
        catch (JsonProcessingException exception) { throw unavailable(); }
    }

    private void validate(ApprovalWorkDtos.SearchFilter filter) {
        ApprovalDraftService.validatePage(filter.page(), filter.size());
        if (filter.query() == null || filter.query().length() > 200 || filter.status() == null || filter.priority() == null
                || filter.due() == null || filter.sort() == null
                || (filter.minRiskScore() != null && (filter.minRiskScore() < 0 || filter.minRiskScore() > 100))
                || !Set.of("", "PENDING", "CLAIMED", "INFO_REQUESTED", "APPROVED", "REJECTED", "DRAFT", "SUBMITTED", "IN_REVIEW", "NEEDS_INFO", "WITHDRAWN", "CANCELLED").contains(filter.status())
                || !Set.of("", "LOW", "NORMAL", "HIGH", "URGENT").contains(filter.priority())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid Approval search filter.");
        }
    }

    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Current list authority changed or is unavailable."); }
    private record Delegation(List<Long> users, String roles) { }
}
