package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.PrivilegedAccessDtos;
import com.dwp.services.auth.entity.ActivePrivilegedGrant;
import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.entity.DirectoryGroup;
import com.dwp.services.auth.entity.EmergencyAccessPrincipal;
import com.dwp.services.auth.entity.PrivilegedAccessApproval;
import com.dwp.services.auth.entity.PrivilegedAccessPolicy;
import com.dwp.services.auth.entity.PrivilegedAccessRequest;
import com.dwp.services.auth.entity.PrivilegedRoleEligibility;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.ActivePrivilegedGrantRepository;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.EmergencyAccessPrincipalRepository;
import com.dwp.services.auth.repository.PrivilegedAccessApprovalRepository;
import com.dwp.services.auth.repository.PrivilegedAccessPolicyRepository;
import com.dwp.services.auth.repository.PrivilegedAccessRequestRepository;
import com.dwp.services.auth.repository.PrivilegedRoleEligibilityRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.services.auth.service.PrivilegedAccessEvidenceSnapshots.eligibilitySnapshot;
import static com.dwp.services.auth.service.PrivilegedAccessEvidenceSnapshots.policySnapshot;
import static com.dwp.services.auth.service.PrivilegedAccessEvidenceSnapshots.requestSnapshot;

@Service
public class PrivilegedAccessService {

    private static final String ACTIVE = "ACTIVE";

    private final PrivilegedAccessPolicyRepository policyRepository;
    private final PrivilegedRoleEligibilityRepository eligibilityRepository;
    private final PrivilegedAccessRequestRepository requestRepository;
    private final PrivilegedAccessApprovalRepository approvalRepository;
    private final ActivePrivilegedGrantRepository grantRepository;
    private final EmergencyAccessPrincipalRepository emergencyPrincipalRepository;
    private final RoleRepository roleRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final UserRepository userRepository;
    private final DirectoryGroupRepository groupRepository;
    private final AuthSessionRepository sessionRepository;
    private final RoleDelegationPolicyService delegationPolicyService;
    private final IdentityAuditService auditService;
    private final PrivilegedAccessRolloutGate rolloutGate;

    public PrivilegedAccessService(
            PrivilegedAccessPolicyRepository policyRepository,
            PrivilegedRoleEligibilityRepository eligibilityRepository,
            PrivilegedAccessRequestRepository requestRepository,
            PrivilegedAccessApprovalRepository approvalRepository,
            ActivePrivilegedGrantRepository grantRepository,
            EmergencyAccessPrincipalRepository emergencyPrincipalRepository,
            RoleRepository roleRepository,
            RoleMemberRepository roleMemberRepository,
            UserRepository userRepository,
            DirectoryGroupRepository groupRepository,
            AuthSessionRepository sessionRepository,
            RoleDelegationPolicyService delegationPolicyService,
            IdentityAuditService auditService,
            PrivilegedAccessRolloutGate rolloutGate) {
        this.policyRepository = policyRepository;
        this.eligibilityRepository = eligibilityRepository;
        this.requestRepository = requestRepository;
        this.approvalRepository = approvalRepository;
        this.grantRepository = grantRepository;
        this.emergencyPrincipalRepository = emergencyPrincipalRepository;
        this.roleRepository = roleRepository;
        this.roleMemberRepository = roleMemberRepository;
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.sessionRepository = sessionRepository;
        this.delegationPolicyService = delegationPolicyService;
        this.auditService = auditService;
        this.rolloutGate = rolloutGate;
    }

    @Transactional(readOnly = true)
    public List<PrivilegedAccessDtos.PolicySummary> policies(Long tenantId) {
        List<PrivilegedAccessPolicy> policies = policyRepository
                .findByTenantIdOrderByRoleIdAsc(tenantId);
        Map<Long, Role> roles = rolesById(tenantId, policies.stream()
                .map(PrivilegedAccessPolicy::getRoleId).toList());
        return policies.stream()
                .filter(policy -> roles.containsKey(policy.getRoleId()))
                .map(policy -> policySummary(policy, roles.get(policy.getRoleId())))
                .toList();
    }

    @Transactional
    public PrivilegedAccessDtos.PolicySummary updatePolicy(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long policyId,
            PrivilegedAccessDtos.UpdatePolicyRequest request) {
        rolloutGate.requirePolicyRemainsDisabled(
                request.activationMode(), request.emergencyMode());
        PrivilegedAccessPolicy policy = policyRepository.findById(policyId)
                .filter(value -> tenantId.equals(value.getTenantId()))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(policy.getVersion(), request.version());
        Role role = requireRole(tenantId, policy.getRoleId());
        requireAssignable(actorId, tenantId, role, correlationId);
        Map<String, Object> before = policySnapshot(policy);
        policy.setActivationMode(request.activationMode());
        policy.setMaximumDurationMinutes(request.maximumDurationMinutes());
        policy.setAssuranceLevel(request.assuranceLevel());
        policy.setApprovalQuorum(request.approvalQuorum().shortValue());
        policy.setEmergencyMode(request.emergencyMode());
        policy.setTicketRequired(request.ticketRequired());
        policy.setLifecycleState(request.lifecycleState());
        policy.setUpdatedBy(actorId);
        policy = policyRepository.saveAndFlush(policy);
        if (!ACTIVE.equals(policy.getLifecycleState())
                || "DISABLED".equals(policy.getActivationMode())) {
            revokeActiveRoleGrants(
                    tenantId, actorId, correlationId, role.getRoleId(),
                    "The privileged access policy was disabled.");
        }
        auditService.success(
                tenantId, actorId, "access.privileged-policy.updated",
                "PRIVILEGED_ACCESS_POLICY", policyId.toString(), correlationId,
                before, policySnapshot(policy));
        return policySummary(policy, role);
    }

    @Transactional(readOnly = true)
    public List<PrivilegedAccessDtos.EligibilitySummary> eligibilities(Long tenantId) {
        return eligibilitySummaries(
                tenantId,
                eligibilityRepository.findByTenantIdOrderByCreatedAtDesc(tenantId));
    }

    @Transactional(readOnly = true)
    public List<PrivilegedAccessDtos.EligibilitySummary> myEligibilities(
            Long tenantId,
            Long userId) {
        return eligibilitySummaries(
                tenantId,
                eligibilityRepository.findEffectiveForUser(tenantId, userId, Instant.now()));
    }

    @Transactional
    public PrivilegedAccessDtos.EligibilitySummary createEligibility(
            Long tenantId,
            Long actorId,
            String correlationId,
            PrivilegedAccessDtos.CreateEligibilityRequest request) {
        Role role = requireRole(tenantId, request.roleId());
        requireAssignable(actorId, tenantId, role, correlationId);
        requirePrincipal(tenantId, request.principalType(), request.principalId());
        requireActivePolicy(tenantId, role.getRoleId());
        String scopeRef = normalizedScopeRef(request.scopeType(), request.scopeRef());
        if (request.validFrom() != null && request.validTo() != null
                && !request.validTo().isAfter(request.validFrom())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The eligibility window is invalid.");
        }
        PrivilegedRoleEligibility eligibility = PrivilegedRoleEligibility.builder()
                .privilegedRoleEligibilityId(UUID.randomUUID())
                .tenantId(tenantId)
                .principalType(request.principalType())
                .principalId(request.principalId())
                .roleId(role.getRoleId())
                .scopeType(request.scopeType())
                .scopeRef(scopeRef)
                .validFrom(request.validFrom())
                .validTo(request.validTo())
                .justification(request.justification().trim())
                .lifecycleState(ACTIVE)
                .build();
        eligibility.setCreatedBy(actorId);
        eligibility.setUpdatedBy(actorId);
        try {
            eligibility = eligibilityRepository.saveAndFlush(eligibility);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This principal already has the active role eligibility.",
                    exception);
        }
        auditService.success(
                tenantId, actorId, "access.privileged-eligibility.created",
                "PRIVILEGED_ROLE_ELIGIBILITY",
                eligibility.getPrivilegedRoleEligibilityId().toString(), correlationId,
                null, eligibilitySnapshot(eligibility));
        return eligibilitySummaries(tenantId, List.of(eligibility)).get(0);
    }

    @Transactional
    public PrivilegedAccessDtos.EligibilitySummary revokeEligibility(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID eligibilityId,
            Long expectedVersion) {
        PrivilegedRoleEligibility eligibility = eligibilityRepository
                .findByPrivilegedRoleEligibilityIdAndTenantId(eligibilityId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(eligibility.getVersion(), expectedVersion);
        Role role = requireAnyRole(tenantId, eligibility.getRoleId());
        if (ACTIVE.equals(role.getStatus())) {
            requireAssignable(actorId, tenantId, role, correlationId);
        }
        if (!ACTIVE.equals(eligibility.getLifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The eligibility is not active.");
        }
        Map<String, Object> before = eligibilitySnapshot(eligibility);
        eligibility.setLifecycleState("REVOKED");
        eligibility.setUpdatedBy(actorId);
        eligibility = eligibilityRepository.saveAndFlush(eligibility);
        for (PrivilegedAccessRequest accessRequest : requestRepository
                .findByTenantIdAndEligibilityIdAndLifecycleState(
                        tenantId, eligibilityId, ACTIVE)) {
            revokeRequest(
                    tenantId, actorId, correlationId, accessRequest,
                    "The underlying role eligibility was revoked.");
        }
        auditService.success(
                tenantId, actorId, "access.privileged-eligibility.revoked",
                "PRIVILEGED_ROLE_ELIGIBILITY", eligibilityId.toString(), correlationId,
                before, eligibilitySnapshot(eligibility));
        return eligibilitySummaries(tenantId, List.of(eligibility)).get(0);
    }

    @Transactional
    public PrivilegedAccessDtos.RequestSummary requestActivation(
            Long tenantId,
            Long requesterId,
            String callerAssurance,
            String correlationId,
            PrivilegedAccessDtos.ActivationRequest request) {
        rolloutGate.requireActivationEnabled();
        Instant now = Instant.now();
        PrivilegedRoleEligibility eligibility = null;
        Role role;
        String scopeType;
        String scopeRef;
        if ("JIT".equals(request.requestType())) {
            if (request.eligibilityId() == null || request.roleId() != null) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "JIT activation must reference exactly one eligibility.");
            }
            eligibility = eligibilityRepository.findEffectiveForUser(tenantId, requesterId, now)
                    .stream()
                    .filter(value -> request.eligibilityId().equals(
                            value.getPrivilegedRoleEligibilityId()))
                    .findFirst()
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.FORBIDDEN,
                            "The role eligibility is missing, expired, or outside this identity."));
            role = requireRole(tenantId, eligibility.getRoleId());
            scopeType = eligibility.getScopeType();
            scopeRef = eligibility.getScopeRef();
        } else {
            if (request.roleId() == null || request.eligibilityId() != null) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Emergency activation must reference exactly one role.");
            }
            requireEmergencyPrincipal(tenantId, requesterId, now);
            role = requireRole(tenantId, request.roleId());
            scopeType = "TENANT";
            scopeRef = null;
        }

        PrivilegedAccessPolicy policy = requireActivePolicy(tenantId, role.getRoleId());
        validateActivationPolicy(
                tenantId, requesterId, correlationId, callerAssurance, request, policy);
        String lifecycleState;
        short quorum;
        if ("EMERGENCY".equals(request.requestType())) {
            if ("DISABLED".equals(policy.getEmergencyMode())) {
                denied(tenantId, requesterId, correlationId, role, "EMERGENCY_ACCESS_DISABLED");
                throw new BaseException(ErrorCode.FORBIDDEN, "Emergency access is disabled for this role.");
            }
            quorum = "DUAL_APPROVAL".equals(policy.getEmergencyMode()) ? (short) 2 : 0;
            lifecycleState = quorum == 0 ? ACTIVE : "PENDING_APPROVAL";
        } else {
            quorum = "SELF_SERVICE".equals(policy.getActivationMode())
                    ? 0
                    : policy.getApprovalQuorum();
            lifecycleState = quorum == 0 ? ACTIVE : "PENDING_APPROVAL";
        }

        PrivilegedAccessRequest accessRequest = PrivilegedAccessRequest.builder()
                .privilegedAccessRequestId(UUID.randomUUID())
                .tenantId(tenantId)
                .requesterUserId(requesterId)
                .roleId(role.getRoleId())
                .eligibilityId(eligibility == null
                        ? null
                        : eligibility.getPrivilegedRoleEligibilityId())
                .requestType(request.requestType())
                .scopeType(scopeType)
                .scopeRef(scopeRef)
                .durationMinutes(request.durationMinutes())
                .justification(request.justification().trim())
                .ticketReference(trimToNull(request.ticketReference()))
                .assuranceLevel(callerAssurance)
                .approvalQuorum(quorum)
                .lifecycleState(lifecycleState)
                .requestedAt(now)
                .build();
        accessRequest.setCreatedBy(requesterId);
        accessRequest.setUpdatedBy(requesterId);
        try {
            accessRequest = requestRepository.saveAndFlush(accessRequest);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An activation request for this role and scope is already open.",
                    exception);
        }
        if (ACTIVE.equals(lifecycleState)) {
            accessRequest = activate(accessRequest, requesterId, correlationId, now);
        }
        auditService.success(
                tenantId, requesterId, "access.privileged-activation.requested",
                "PRIVILEGED_ACCESS_REQUEST",
                accessRequest.getPrivilegedAccessRequestId().toString(), correlationId,
                null, requestSnapshot(accessRequest));
        return requestSummary(tenantId, accessRequest);
    }

    @Transactional
    public PrivilegedAccessDtos.RequestSummary decide(
            Long tenantId,
            Long approverId,
            String correlationId,
            UUID requestId,
            PrivilegedAccessDtos.ApprovalDecisionRequest decision) {
        rolloutGate.requireActivationEnabled();
        PrivilegedAccessRequest accessRequest = requireRequest(tenantId, requestId);
        requireVersion(accessRequest.getVersion(), decision.version());
        if (!"PENDING_APPROVAL".equals(accessRequest.getLifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The request is not pending approval.");
        }
        if (approverId.equals(accessRequest.getRequesterUserId())) {
            denied(
                    tenantId, approverId, correlationId,
                    requireRole(tenantId, accessRequest.getRoleId()),
                    "SELF_APPROVAL_NOT_ALLOWED");
            throw new BaseException(ErrorCode.FORBIDDEN, "Requesters cannot approve their own access.");
        }
        Role role = requireRole(tenantId, accessRequest.getRoleId());
        requireAssignable(approverId, tenantId, role, correlationId);
        if (approvalRepository.existsByPrivilegedAccessRequestIdAndApproverUserId(
                requestId, approverId)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "This approver already decided the request.");
        }
        PrivilegedAccessApproval approval = PrivilegedAccessApproval.builder()
                .privilegedAccessApprovalId(UUID.randomUUID())
                .privilegedAccessRequestId(requestId)
                .tenantId(tenantId)
                .approverUserId(approverId)
                .decision(decision.decision())
                .reason(decision.reason().trim())
                .decidedAt(Instant.now())
                .build();
        approval.setCreatedBy(approverId);
        approval.setUpdatedBy(approverId);
        approvalRepository.saveAndFlush(approval);

        Map<String, Object> before = requestSnapshot(accessRequest);
        if ("DENY".equals(decision.decision())) {
            accessRequest.setLifecycleState("DENIED");
            accessRequest.setDecidedAt(approval.getDecidedAt());
        } else if (approvalRepository.countByPrivilegedAccessRequestIdAndDecision(
                requestId, "APPROVE") >= accessRequest.getApprovalQuorum()) {
            accessRequest = activate(accessRequest, approverId, correlationId, Instant.now());
        }
        accessRequest.setUpdatedBy(approverId);
        accessRequest = requestRepository.saveAndFlush(accessRequest);
        auditService.success(
                tenantId, approverId, "access.privileged-activation.decided",
                "PRIVILEGED_ACCESS_REQUEST", requestId.toString(), correlationId,
                before, requestSnapshot(accessRequest));
        return requestSummary(tenantId, accessRequest);
    }

    @Transactional
    public PrivilegedAccessDtos.RequestSummary revoke(
            Long tenantId,
            Long actorId,
            boolean tenantAdministrator,
            String correlationId,
            UUID requestId,
            PrivilegedAccessDtos.RevokeRequest request) {
        PrivilegedAccessRequest accessRequest = requireRequest(tenantId, requestId);
        requireVersion(accessRequest.getVersion(), request.version());
        if (!tenantAdministrator && !actorId.equals(accessRequest.getRequesterUserId())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        if (tenantAdministrator && !actorId.equals(accessRequest.getRequesterUserId())) {
            Role role = requireAnyRole(tenantId, accessRequest.getRoleId());
            if (ACTIVE.equals(role.getStatus())) {
                requireAssignable(
                        actorId, tenantId, role, correlationId);
            }
        }
        if ("PENDING_APPROVAL".equals(accessRequest.getLifecycleState())) {
            accessRequest.setLifecycleState("CANCELLED");
            accessRequest.setDecidedAt(Instant.now());
            accessRequest.setUpdatedBy(actorId);
            accessRequest = requestRepository.saveAndFlush(accessRequest);
        } else if (ACTIVE.equals(accessRequest.getLifecycleState())) {
            revokeRequest(tenantId, actorId, correlationId, accessRequest, request.reason().trim());
            accessRequest = requireRequest(tenantId, requestId);
        } else {
            throw new BaseException(ErrorCode.INVALID_STATE, "The request cannot be revoked in its current state.");
        }
        return requestSummary(tenantId, accessRequest);
    }

    @Transactional
    public List<PrivilegedAccessDtos.RequestSummary> requests(
            Long tenantId,
            Long actorId,
            boolean tenantAdministrator) {
        expireStale(tenantId);
        List<PrivilegedAccessRequest> requests = tenantAdministrator
                ? requestRepository.findByTenantIdOrderByRequestedAtDesc(tenantId)
                : requestRepository.findByTenantIdAndRequesterUserIdOrderByRequestedAtDesc(
                        tenantId, actorId);
        return requests.stream().map(request -> requestSummary(tenantId, request)).toList();
    }

    @Transactional
    public int expireStaleBatch(int batchSize) {
        int safeBatchSize = Math.min(1000, Math.max(1, batchSize));
        Instant now = Instant.now();
        List<ActivePrivilegedGrant> grants = grantRepository.findExpired(
                now, PageRequest.of(0, safeBatchSize));
        int processed = grants.size();
        Set<UUID> processedRequests = new LinkedHashSet<>();
        Set<String> affectedUsers = new LinkedHashSet<>();
        for (ActivePrivilegedGrant grant : grants) {
            grant.setRevokedAt(now);
            grant.setRevokeReason("The time-bound grant expired.");
            grantRepository.save(grant);
            PrivilegedAccessRequest request = requestRepository
                    .findByPrivilegedAccessRequestIdAndTenantId(
                            grant.getPrivilegedAccessRequestId(), grant.getTenantId())
                    .orElse(null);
            if (request != null && ACTIVE.equals(request.getLifecycleState())) {
                expireRequest(request, now);
                processedRequests.add(request.getPrivilegedAccessRequestId());
                affectedUsers.add(request.getTenantId() + ":" + request.getRequesterUserId());
            }
        }
        int remaining = Math.max(0, safeBatchSize - processed);
        if (remaining > 0) {
            for (PrivilegedAccessRequest request : requestRepository.findExpired(
                    now, PageRequest.of(0, remaining))) {
                if (processedRequests.add(request.getPrivilegedAccessRequestId())) {
                    expireRequest(request, now);
                    processed++;
                    affectedUsers.add(request.getTenantId() + ":" + request.getRequesterUserId());
                }
            }
        }
        affectedUsers.forEach(key -> {
            String[] parts = key.split(":", 2);
            invalidateUser(Long.parseLong(parts[0]), Long.parseLong(parts[1]), null);
        });
        return processed;
    }

    @Transactional(readOnly = true)
    public List<PrivilegedAccessDtos.EmergencyPrincipalSummary> emergencyPrincipals(Long tenantId) {
        List<EmergencyAccessPrincipal> principals = emergencyPrincipalRepository
                .findByTenantIdOrderByReviewDueAtAsc(tenantId);
        Map<Long, User> users = usersById(tenantId, principals.stream()
                .map(EmergencyAccessPrincipal::getUserId).toList());
        return principals.stream().map(principal -> emergencySummary(
                principal, users.get(principal.getUserId()))).toList();
    }

    @Transactional
    public PrivilegedAccessDtos.EmergencyPrincipalSummary registerEmergencyPrincipal(
            Long tenantId,
            Long actorId,
            String correlationId,
            PrivilegedAccessDtos.RegisterEmergencyPrincipalRequest request) {
        if (actorId.equals(request.userId())) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Emergency principals cannot register themselves.");
        }
        User user = userRepository.findByUserIdAndTenantId(request.userId(), tenantId)
                .filter(value -> ACTIVE.equals(value.getStatus()))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        EmergencyAccessPrincipal principal = EmergencyAccessPrincipal.builder()
                .emergencyAccessPrincipalId(UUID.randomUUID())
                .tenantId(tenantId)
                .userId(user.getUserId())
                .justification(request.justification().trim())
                .reviewDueAt(request.reviewDueAt())
                .lifecycleState(ACTIVE)
                .build();
        principal.setCreatedBy(actorId);
        principal.setUpdatedBy(actorId);
        try {
            principal = emergencyPrincipalRepository.saveAndFlush(principal);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The user is already registered as an emergency principal.",
                    exception);
        }
        auditService.success(
                tenantId, actorId, "access.emergency-principal.registered",
                "EMERGENCY_ACCESS_PRINCIPAL",
                principal.getEmergencyAccessPrincipalId().toString(), correlationId,
                null, Map.of(
                        "userId", user.getUserId(),
                        "reviewDueAt", principal.getReviewDueAt(),
                        "lifecycleState", principal.getLifecycleState()));
        return emergencySummary(principal, user);
    }

    private PrivilegedAccessRequest activate(
            PrivilegedAccessRequest request,
            Long actorId,
            String correlationId,
            Instant now) {
        rolloutGate.requireActivationEnabled();
        Role role = requireRole(request.getTenantId(), request.getRoleId());
        Set<String> effective = effectiveRoleCodes(request.getTenantId(), request.getRequesterUserId());
        Set<String> requested = new LinkedHashSet<>(effective);
        requested.add(role.getCode());
        RoleDelegationPolicyService.RoleSetDecision decision = delegationPolicyService
                .evaluateRoleSet(effective, effective, requested);
        if (!decision.allowed()) {
            denied(
                    request.getTenantId(), actorId, correlationId, role,
                    "ACTIVATION_" + decision.reason());
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The role activation violates a separation-of-duties policy.");
        }
        Instant expiresAt = now.plus(request.getDurationMinutes(), ChronoUnit.MINUTES);
        ActivePrivilegedGrant grant = ActivePrivilegedGrant.builder()
                .activePrivilegedGrantId(UUID.randomUUID())
                .privilegedAccessRequestId(request.getPrivilegedAccessRequestId())
                .tenantId(request.getTenantId())
                .userId(request.getRequesterUserId())
                .roleId(request.getRoleId())
                .scopeType(request.getScopeType())
                .scopeRef(request.getScopeRef())
                .activatedAt(now)
                .expiresAt(expiresAt)
                .build();
        grant.setCreatedBy(actorId);
        grant.setUpdatedBy(actorId);
        grantRepository.saveAndFlush(grant);
        request.setLifecycleState(ACTIVE);
        request.setDecidedAt(now);
        request.setActivatedAt(now);
        request.setExpiresAt(expiresAt);
        request.setUpdatedBy(actorId);
        PrivilegedAccessRequest saved = requestRepository.saveAndFlush(request);
        invalidateUser(request.getTenantId(), request.getRequesterUserId(), actorId);
        return saved;
    }

    private void revokeRequest(
            Long tenantId,
            Long actorId,
            String correlationId,
            PrivilegedAccessRequest request,
            String reason) {
        Instant now = Instant.now();
        grantRepository.findByPrivilegedAccessRequestIdAndTenantId(
                        request.getPrivilegedAccessRequestId(), tenantId)
                .filter(grant -> grant.getRevokedAt() == null)
                .ifPresent(grant -> {
                    grant.setRevokedAt(now);
                    grant.setRevokedBy(actorId);
                    grant.setRevokeReason(reason);
                    grant.setUpdatedBy(actorId);
                    grantRepository.save(grant);
                });
        Map<String, Object> before = requestSnapshot(request);
        request.setLifecycleState("REVOKED");
        request.setRevokedAt(now);
        request.setUpdatedBy(actorId);
        requestRepository.saveAndFlush(request);
        invalidateUser(tenantId, request.getRequesterUserId(), actorId);
        auditService.success(
                tenantId, actorId, "access.privileged-activation.revoked",
                "PRIVILEGED_ACCESS_REQUEST",
                request.getPrivilegedAccessRequestId().toString(), correlationId,
                before, requestSnapshot(request));
    }

    private void revokeActiveRoleGrants(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long roleId,
            String reason) {
        for (ActivePrivilegedGrant grant : grantRepository.findActiveForRole(
                tenantId, roleId, Instant.now())) {
            PrivilegedAccessRequest request = requireRequest(
                    tenantId, grant.getPrivilegedAccessRequestId());
            revokeRequest(tenantId, actorId, correlationId, request, reason);
        }
    }

    private void expireStale(Long tenantId) {
        Instant now = Instant.now();
        Set<Long> affectedUsers = new LinkedHashSet<>();
        for (ActivePrivilegedGrant grant : grantRepository
                .findByTenantIdAndRevokedAtIsNullAndExpiresAtLessThanEqual(tenantId, now)) {
            grant.setRevokedAt(now);
            grant.setRevokeReason("The time-bound grant expired.");
            grantRepository.save(grant);
            affectedUsers.add(grant.getUserId());
        }
        for (PrivilegedAccessRequest request : requestRepository
                .findByTenantIdAndLifecycleStateAndExpiresAtLessThanEqual(tenantId, ACTIVE, now)) {
            expireRequest(request, now);
            affectedUsers.add(request.getRequesterUserId());
        }
        affectedUsers.forEach(userId -> invalidateUser(tenantId, userId, null));
    }

    private void expireRequest(PrivilegedAccessRequest request, Instant expiredAt) {
        Map<String, Object> before = requestSnapshot(request);
        request.setLifecycleState("EXPIRED");
        request.setRevokedAt(expiredAt);
        request.setUpdatedBy(null);
        requestRepository.save(request);
        auditService.success(
                request.getTenantId(), null, "access.privileged-activation.expired",
                "PRIVILEGED_ACCESS_REQUEST",
                request.getPrivilegedAccessRequestId().toString(),
                "system:privileged-access-expiry", before, requestSnapshot(request));
    }

    private void validateActivationPolicy(
            Long tenantId,
            Long requesterId,
            String correlationId,
            String callerAssurance,
            PrivilegedAccessDtos.ActivationRequest request,
            PrivilegedAccessPolicy policy) {
        Role role = requireRole(tenantId, policy.getRoleId());
        if ("DISABLED".equals(policy.getActivationMode())) {
            denied(tenantId, requesterId, correlationId, role, "ACTIVATION_DISABLED");
            throw new BaseException(ErrorCode.FORBIDDEN, "Activation is disabled for this role.");
        }
        if (request.durationMinutes() > policy.getMaximumDurationMinutes()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The requested duration exceeds the role activation policy.");
        }
        if (Boolean.TRUE.equals(policy.getTicketRequired())
                && trimToNull(request.ticketReference()) == null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A governed ticket reference is required for this role.");
        }
        if (assuranceRank(callerAssurance) < assuranceRank(policy.getAssuranceLevel())) {
            denied(tenantId, requesterId, correlationId, role, "STEP_UP_REQUIRED");
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The current session does not satisfy the role assurance requirement.");
        }
    }

    private void requireAssignable(
            Long actorId,
            Long tenantId,
            Role role,
            String correlationId) {
        RoleDelegationPolicyService.DelegationContext context = delegationPolicyService
                .resolveForApproval(tenantId, actorId);
        if (!context.assignableRolesByCode().containsKey(role.getCode())) {
            denied(tenantId, actorId, correlationId, role, "ROLE_OUTSIDE_DELEGATION_BOUNDARY");
            throw new BaseException(ErrorCode.FORBIDDEN, "The role is outside the delegation boundary.");
        }
    }

    private Object requirePrincipal(Long tenantId, String type, Long principalId) {
        if ("USER".equals(type)) {
            return userRepository.findByUserIdAndTenantId(principalId, tenantId)
                    .filter(user -> ACTIVE.equals(user.getStatus()))
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        }
        return groupRepository.findByGroupIdAndTenantId(principalId, tenantId)
                .filter(group -> ACTIVE.equals(group.getStatus()))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private EmergencyAccessPrincipal requireEmergencyPrincipal(
            Long tenantId,
            Long userId,
            Instant now) {
        return emergencyPrincipalRepository.findByTenantIdAndUserId(tenantId, userId)
                .filter(principal -> ACTIVE.equals(principal.getLifecycleState()))
                .filter(principal -> principal.getReviewDueAt().isAfter(now))
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN,
                        "The identity is not an active, reviewed emergency principal."));
    }

    private PrivilegedAccessPolicy requireActivePolicy(Long tenantId, Long roleId) {
        return policyRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .filter(policy -> ACTIVE.equals(policy.getLifecycleState()))
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN,
                        "No active privileged access policy exists for this role."));
    }

    private PrivilegedAccessRequest requireRequest(Long tenantId, UUID requestId) {
        return requestRepository.findByPrivilegedAccessRequestIdAndTenantId(requestId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private Role requireRole(Long tenantId, Long roleId) {
        return roleRepository.findByRoleIdAndTenantId(roleId, tenantId)
                .filter(role -> ACTIVE.equals(role.getStatus()))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private Role requireAnyRole(Long tenantId, Long roleId) {
        return roleRepository.findByRoleIdAndTenantId(roleId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private Set<String> effectiveRoleCodes(Long tenantId, Long userId) {
        return roleRepository.findByRoleIdIn(roleMemberRepository.findRoleIds(tenantId, userId))
                .stream()
                .filter(role -> tenantId.equals(role.getTenantId()))
                .filter(role -> ACTIVE.equals(role.getStatus()))
                .map(Role::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void invalidateUser(Long tenantId, Long userId, Long actorId) {
        User user = userRepository.findByUserIdAndTenantId(userId, tenantId).orElse(null);
        if (user != null) {
            user.setAccessRevision(valueOrZero(user.getAccessRevision()) + 1L);
            user.setUpdatedBy(actorId);
            userRepository.save(user);
        }
        Instant now = Instant.now();
        List<AuthSession> sessions = sessionRepository
                .findByTenantIdAndUserIdAndRevokedAtIsNull(tenantId, userId);
        sessions.forEach(session -> {
            session.setRevokedAt(now);
            session.setUpdatedBy(actorId);
        });
        sessionRepository.saveAll(sessions);
    }

    private List<PrivilegedAccessDtos.EligibilitySummary> eligibilitySummaries(
            Long tenantId,
            List<PrivilegedRoleEligibility> eligibilities) {
        Map<Long, Role> roles = rolesById(tenantId, eligibilities.stream()
                .map(PrivilegedRoleEligibility::getRoleId).toList());
        Map<Long, User> users = usersById(
                tenantId,
                eligibilities.stream()
                        .filter(value -> "USER".equals(value.getPrincipalType()))
                        .map(PrivilegedRoleEligibility::getPrincipalId)
                        .toList());
        Map<Long, DirectoryGroup> groups = groupRepository.findAllById(
                        eligibilities.stream()
                                .filter(value -> "GROUP".equals(value.getPrincipalType()))
                                .map(PrivilegedRoleEligibility::getPrincipalId)
                                .toList())
                .stream()
                .filter(group -> tenantId.equals(group.getTenantId()))
                .collect(Collectors.toMap(DirectoryGroup::getGroupId, Function.identity()));
        return eligibilities.stream()
                .filter(value -> roles.containsKey(value.getRoleId()))
                .map(value -> {
                    Role role = roles.get(value.getRoleId());
                    String principalName = "USER".equals(value.getPrincipalType())
                            ? displayName(users.get(value.getPrincipalId()))
                            : displayName(groups.get(value.getPrincipalId()));
                    return new PrivilegedAccessDtos.EligibilitySummary(
                            value.getPrivilegedRoleEligibilityId(), value.getPrincipalType(),
                            value.getPrincipalId(), principalName, role.getRoleId(), role.getCode(),
                            role.getName(), value.getScopeType(), value.getScopeRef(),
                            value.getValidFrom(), value.getValidTo(), value.getJustification(),
                            value.getLifecycleState(), valueOrZero(value.getVersion()));
                })
                .toList();
    }

    private PrivilegedAccessDtos.RequestSummary requestSummary(
            Long tenantId,
            PrivilegedAccessRequest request) {
        Role role = requireAnyRole(tenantId, request.getRoleId());
        User requester = userRepository
                .findByUserIdAndTenantId(request.getRequesterUserId(), tenantId)
                .orElse(null);
        List<PrivilegedAccessApproval> approvals = approvalRepository
                .findByPrivilegedAccessRequestIdOrderByDecidedAtAsc(
                        request.getPrivilegedAccessRequestId());
        Map<Long, User> approvers = usersById(tenantId, approvals.stream()
                .map(PrivilegedAccessApproval::getApproverUserId).toList());
        return new PrivilegedAccessDtos.RequestSummary(
                request.getPrivilegedAccessRequestId(), request.getRequesterUserId(),
                displayName(requester), role.getRoleId(), role.getCode(), role.getName(),
                request.getEligibilityId(), request.getRequestType(), request.getScopeType(),
                request.getScopeRef(), request.getDurationMinutes(), request.getJustification(),
                request.getTicketReference(), request.getAssuranceLevel(),
                request.getApprovalQuorum(), request.getLifecycleState(), request.getRequestedAt(),
                request.getActivatedAt(), request.getExpiresAt(), request.getRevokedAt(),
                valueOrZero(request.getVersion()),
                approvals.stream().map(approval -> new PrivilegedAccessDtos.ApprovalSummary(
                        approval.getApproverUserId(),
                        displayName(approvers.get(approval.getApproverUserId())),
                        approval.getDecision(), approval.getReason(), approval.getDecidedAt()))
                        .toList());
    }

    private PrivilegedAccessDtos.PolicySummary policySummary(
            PrivilegedAccessPolicy policy,
            Role role) {
        return new PrivilegedAccessDtos.PolicySummary(
                policy.getPrivilegedAccessPolicyId(), role.getRoleId(), role.getCode(),
                role.getName(), policy.getActivationMode(), policy.getMaximumDurationMinutes(),
                policy.getAssuranceLevel(), policy.getApprovalQuorum(), policy.getEmergencyMode(),
                Boolean.TRUE.equals(policy.getTicketRequired()), policy.getLifecycleState(),
                valueOrZero(policy.getVersion()));
    }

    private PrivilegedAccessDtos.EmergencyPrincipalSummary emergencySummary(
            EmergencyAccessPrincipal principal,
            User user) {
        return new PrivilegedAccessDtos.EmergencyPrincipalSummary(
                principal.getEmergencyAccessPrincipalId(), principal.getUserId(),
                displayName(user), principal.getJustification(), principal.getReviewDueAt(),
                principal.getLifecycleState(), valueOrZero(principal.getVersion()));
    }

    private Map<Long, Role> rolesById(Long tenantId, Collection<Long> roleIds) {
        if (roleIds.isEmpty()) return Map.of();
        return roleRepository.findByRoleIdIn(roleIds.stream().distinct().toList()).stream()
                .filter(role -> tenantId.equals(role.getTenantId()))
                .collect(Collectors.toMap(Role::getRoleId, Function.identity()));
    }

    private Map<Long, User> usersById(Long tenantId, Collection<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        return userRepository.findByTenantIdAndUserIdIn(
                        tenantId, userIds.stream().distinct().toList())
                .stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity()));
    }

    private String displayName(Object principal) {
        if (principal instanceof User user) return user.getDisplayName();
        if (principal instanceof DirectoryGroup group) return group.getDisplayName();
        return null;
    }

    private void denied(
            Long tenantId,
            Long actorId,
            String correlationId,
            Role role,
            String reason) {
        auditService.denied(
                tenantId, actorId, "access.privileged-activation.rejected",
                "ROLE", role.getRoleId().toString(), correlationId, reason,
                Map.of("roleCode", role.getCode()));
    }

    private int assuranceRank(String assurance) {
        return switch (assurance == null ? "SESSION" : assurance) {
            case "PHISHING_RESISTANT" -> 2;
            case "MFA" -> 1;
            default -> 0;
        };
    }

    private String normalizedScopeRef(String scopeType, String scopeRef) {
        String normalized = trimToNull(scopeRef);
        if (("TENANT".equals(scopeType) && normalized != null)
                || (!"TENANT".equals(scopeType) && normalized == null)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The access scope is invalid.");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void requireVersion(Long actual, Long expected) {
        if (!Objects.equals(valueOrZero(actual), expected)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Privileged access data changed after it was loaded. Refresh and try again.");
        }
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
