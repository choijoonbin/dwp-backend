package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.dwp.services.platform.workplace.WorkplaceExperienceCollaborationDtos.*;

@Service
public class WorkplaceExperienceCollaborationService {
    private final WorkplaceExperienceCollaborationRepository repository;
    private final WorkplaceRuntimeGovernance runtime;
    private final WorkplaceSpatialGovernanceRepository auditRepository;
    private final ObjectMapper mapper;

    public WorkplaceExperienceCollaborationService(WorkplaceExperienceCollaborationRepository repository,
            WorkplaceRuntimeGovernance runtime, WorkplaceSpatialGovernanceRepository auditRepository,
            ObjectMapper mapper) {
        this.repository = repository;
        this.runtime = runtime;
        this.auditRepository = auditRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public CollaborationOverview overview(long tenantId, long userId, String groupHeader,
                                           LocalDate from, LocalDate to, UUID groupRef) {
        identity(tenantId, userId);
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 61) {
            throw invalid("A date range of at most 62 days is required.");
        }
        Set<UUID> groups = groups(groupHeader);
        List<ShareableGroup> shareableGroups = groups.stream().sorted().map(ref -> new ShareableGroup(ref, null)).toList();
        if (groupRef != null) {
            if (!groups.contains(groupRef)) throw forbidden();
            groups = Set.of(groupRef);
        }
        SharingPolicy policy = repository.policy(tenantId);
        List<WorkplaceExperienceCollaborationRepository.SharedCandidate> candidates =
                repository.sharedPlans(tenantId, userId, from, to, groups);
        Set<UUID> siteIds = new HashSet<>();
        candidates.stream().map(candidate -> candidate.plan().siteId()).filter(Objects::nonNull).forEach(siteIds::add);
        Set<UUID> viewableSites = runtime.viewableSiteIds(tenantId, userId, groupHeader, siteIds);
        List<SharedWorkPlan> shared = candidates.stream()
                .filter(candidate -> candidate.plan().siteId() == null || viewableSites.contains(candidate.plan().siteId()))
                .filter(candidate -> viewableOwnPlan(tenantId, userId, groupHeader, candidate.plan()))
                .map(candidate -> project(candidate, policy)).filter(Objects::nonNull).toList();
        List<WorkPlan> own = repository.ownPlans(tenantId, userId, from, to).stream()
                .filter(plan -> viewableOwnPlan(tenantId, userId, groupHeader, plan)).toList();
        ConnectorStatus presence = repository.connector(tenantId, ConnectorKind.ACTUAL_PRESENCE);
        // Member projection never exposes an administrator's configuration reference.
        presence = new ConnectorStatus(presence.kind(), presence.provider(), presence.status(), null, null, presence.version());
        return new CollaborationOverview(own, shared, repository.preference(tenantId, userId, false),
                policy, shareableGroups, presence, OffsetDateTime.now());
    }

    @Transactional
    public WorkPlan savePlan(long tenantId, long userId, String groupHeader,
                             String correlationId, WorkPlanRequest request) {
        identity(tenantId, userId);
        if (request == null || request.planDate() == null || request.mode() == null || request.visibility() == null) {
            throw invalid("A date, work mode and visibility are required.");
        }
        if (request.planDate().isBefore(LocalDate.now().minusYears(1))
                || request.planDate().isAfter(LocalDate.now().plusYears(1))) throw invalid("The planned date is outside the supported year.");
        Set<UUID> verifiedGroups = groups(groupHeader);
        if (request.groupRef() != null && !verifiedGroups.contains(request.groupRef())) throw forbidden();
        validateLocation(tenantId, userId, groupHeader, request);
        repository.ensurePolicy(tenantId);
        repository.ensurePreference(tenantId, userId);
        // Plan publication and consent withdrawal serialize on the same member row.
        SharingPreference preference = repository.preference(tenantId, userId, true);
        SharingPolicy policy = repository.policy(tenantId);
        if (request.visibility() != Visibility.PRIVATE) {
            if (!preference.optIn() || !policy.sharingEnabled() || request.groupRef() == null
                    || request.visibility().ordinal() > preference.visibility().ordinal()
                    || request.visibility().ordinal() > policy.maximumVisibility().ordinal()) throw forbidden();
        }
        Optional<WorkPlan> before = repository.ownPlanOnDate(tenantId, userId, request.planDate());
        long retention = repository.retentionDays(tenantId);
        try {
            if (request.version() == null) {
                if (before.isPresent()) throw conflict();
                repository.createPlan(tenantId, userId, UUID.randomUUID(), request, retention);
            } else if (!repository.updatePlan(tenantId, userId, request, retention)) throw conflict();
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The work plan changed. Refresh and retry.", exception);
        }
        WorkPlan after = repository.ownPlanOnDate(tenantId, userId, request.planDate()).orElseThrow(this::conflict);
        audit(tenantId, userId, "workplace.experience.workplan.saved", "WP_WORK_PLAN", after.planId(),
                correlationId, before.orElse(null), after, null);
        return after;
    }

    @Transactional
    public MutationResult deletePlan(long tenantId, long userId, UUID planId, long version, String correlationId) {
        identity(tenantId, userId);
        repository.ensurePreference(tenantId, userId);
        repository.preference(tenantId, userId, true);
        if (version < 0 || !repository.deletePlan(tenantId, userId, planId, version)) throw conflict();
        audit(tenantId, userId, "workplace.experience.workplan.deleted", "WP_WORK_PLAN", planId,
                correlationId, null, Map.of("removed", true), null);
        return new MutationResult(true);
    }

    @Transactional
    public SharingPreference savePreference(long tenantId, long userId, SharingPreferenceRequest request,
                                             String correlationId) {
        identity(tenantId, userId);
        repository.ensurePolicy(tenantId);
        repository.ensurePreference(tenantId, userId);
        SharingPreference before = repository.preference(tenantId, userId, true);
        SharingPolicy policy = repository.policy(tenantId);
        if (request.optIn() && (!policy.sharingEnabled() || request.visibility() == Visibility.PRIVATE
                || request.visibility().ordinal() > policy.maximumVisibility().ordinal())) throw forbidden();
        SharingPreferenceRequest normalized = new SharingPreferenceRequest(request.optIn(),
                request.optIn() ? request.visibility() : Visibility.PRIVATE, request.version());
        if (!repository.updatePreference(tenantId, userId, normalized)) throw conflict();
        if (!request.optIn()) repository.privatizePlans(tenantId, userId);
        SharingPreference after = repository.preference(tenantId, userId, false);
        audit(tenantId, userId, request.optIn() ? "workplace.experience.sharing.enabled" : "workplace.experience.sharing.revoked",
                "WP_SHARING_PREFERENCE", null, correlationId, before, after, null);
        return after;
    }

    @Transactional
    public SharingPreference revokePreference(long tenantId, long userId, long version, String correlationId) {
        return savePreference(tenantId, userId,
                new SharingPreferenceRequest(false, Visibility.PRIVATE, version), correlationId);
    }

    @Transactional(readOnly = true)
    public SharingPolicy policy(long tenantId) { return repository.policy(tenantId); }

    @Transactional
    public SharingPolicy savePolicy(long tenantId, long actorId, SharingPolicyRequest request, String correlationId) {
        identity(tenantId, actorId);
        requireConfirmation(request.reason(), request.confirmed());
        repository.ensurePolicy(tenantId);
        SharingPolicy before = repository.policy(tenantId);
        if (!repository.updatePolicy(tenantId, actorId, request)) throw conflict();
        SharingPolicy after = repository.policy(tenantId);
        audit(tenantId, actorId, "workplace.experience.sharing.policy.updated", "WP_SHARING_POLICY", null,
                correlationId, before, after, request.reason());
        return after;
    }

    @Transactional(readOnly = true)
    public GovernanceOverview governanceOverview(long tenantId) {
        List<ConnectorStatus> statuses = Arrays.stream(ConnectorKind.values()).map(kind -> repository.connector(tenantId, kind)).toList();
        return new GovernanceOverview(repository.policy(tenantId), statuses, repository.privacy(tenantId), OffsetDateTime.now());
    }

    @Transactional
    public ConnectorStatus saveConnector(long tenantId, long actorId, ConnectorKind kind,
                                         ConnectorRequest request, String correlationId) {
        identity(tenantId, actorId);
        requireConfirmation(request.reason(), request.confirmed());
        if (request.provider() == null || !request.provider().matches("[A-Za-z0-9._-]{1,80}")
                || (request.configurationReference() != null && !request.configurationReference().matches("[A-Za-z0-9._:/-]{1,160}"))) {
            throw invalid("Only an allowlisted provider label and opaque configuration reference may be stored.");
        }
        ConnectorStatus before = repository.connector(tenantId, kind);
        repository.ensureConnector(tenantId, kind);
        if (!repository.updateConnector(tenantId, actorId, kind, request)) throw conflict();
        ConnectorStatus after = repository.connector(tenantId, kind);
        audit(tenantId, actorId, "workplace.experience.connector.configured", "WP_CONNECTOR", null,
                correlationId, before, after, request.reason());
        return after;
    }

    void audit(long tenantId, long actorId, String action, String type, UUID targetId,
               String correlationId, Object before, Object after, String reason) {
        var snapshot = mapper.createObjectNode();
        snapshot.set("before", mapper.valueToTree(before));
        snapshot.set("after", mapper.valueToTree(after));
        if (reason != null) {
            snapshot.put("reason", reason.trim());
            snapshot.put("confirmed", true);
        }
        auditRepository.appendAudit(tenantId, actorId, action, type, targetId, correlationId, snapshot);
    }

    static void requireConfirmation(String reason, boolean confirmed) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500 || !confirmed) {
            throw invalid("Review the change, provide a reason and confirm before saving.");
        }
    }

    private SharedWorkPlan project(WorkplaceExperienceCollaborationRepository.SharedCandidate candidate, SharingPolicy policy) {
        WorkPlan plan = candidate.plan();
        int level = Math.min(plan.visibility().ordinal(), Math.min(candidate.preferenceVisibility().ordinal(), policy.maximumVisibility().ordinal()));
        if (level == 0) return null;
        Visibility effective = Visibility.values()[level];
        return new SharedWorkPlan(plan.planId(), candidate.userId(), null, plan.planDate(), plan.mode(), plan.siteId(),
                level >= Visibility.FLOOR.ordinal() ? plan.floorId() : null,
                level >= Visibility.RESOURCE.ordinal() ? plan.resourceId() : null, effective, "WORK_PLAN");
    }

    private boolean viewableOwnPlan(long tenantId, long userId, String groups, WorkPlan plan) {
        if (plan.siteId() == null) return true;
        try { runtime.requireViewAccess(tenantId, userId, groups, plan.siteId(), plan.floorId()); return true; }
        catch (BaseException exception) { if (exception.getErrorCode() == ErrorCode.FORBIDDEN) return false; throw exception; }
    }

    private void validateLocation(long tenantId, long userId, String groups, WorkPlanRequest request) {
        if (request.mode() != PlanMode.OFFICE) {
            if (request.siteId() != null || request.floorId() != null || request.resourceId() != null) throw invalid("Remote and off plans have no Workplace location.");
            return;
        }
        if (request.siteId() == null || (request.resourceId() != null && request.floorId() == null)
                || !repository.locationMatches(tenantId, request.siteId(), request.floorId(), request.resourceId())) {
            throw invalid("Choose a registered location in one site, floor and resource hierarchy.");
        }
        runtime.requireViewAccess(tenantId, userId, groups, request.siteId(), request.floorId());
        if (request.visibility() == Visibility.FLOOR && request.floorId() == null) throw invalid("Floor sharing requires a floor.");
        if (request.visibility() == Visibility.RESOURCE && request.resourceId() == null) throw invalid("Resource sharing requires a resource.");
    }

    private static Set<UUID> groups(String header) {
        if (header == null || header.isBlank()) return Set.of();
        if (header.length() > 16000) throw forbidden();
        try {
            Set<UUID> result = new LinkedHashSet<>();
            for (String value : header.split(",")) result.add(UUID.fromString(value.trim()));
            if (result.size() > 400) throw forbidden();
            return Set.copyOf(result);
        } catch (IllegalArgumentException exception) { throw forbidden(); }
    }

    private static void identity(long tenantId, long userId) { if (tenantId <= 0 || userId <= 0) throw forbidden(); }
    private BaseException conflict() { return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, "The saved version changed. Refresh the original item before retrying."); }
    private static BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    private static BaseException forbidden() { return new BaseException(ErrorCode.FORBIDDEN, "The current member, site or sharing policy does not permit this operation."); }
}
