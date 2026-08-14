package com.dwp.services.people.workforce;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkforceAccessPolicyService {

    private static final Set<String> FIELD_GROUPS = Set.of(
            "DIRECTORY", "WORKER_IDENTIFIERS", "EMPLOYMENT", "JOB_GRADE");
    private static final Set<String> ACTIONS = Set.of("READ", "EXPORT");
    private static final Set<String> GOVERNANCE_ROLES = Set.of("ADMIN", "TENANT_ADMIN");
    private static final String GOVERNANCE_PERMISSION = "ADMIN.WORKFORCE_ACCESS:MANAGE";

    private final WorkforceAccessPolicyRepository repository;
    private final AuditOutboxRecorder audit;
    private final WorkforceAccessDeniedAuditRecorder deniedAudit;

    public WorkforceAccessPolicyService(
            WorkforceAccessPolicyRepository repository,
            AuditOutboxRecorder audit,
            WorkforceAccessDeniedAuditRecorder deniedAudit) {
        this.repository = repository;
        this.audit = audit;
        this.deniedAudit = deniedAudit;
    }

    @Transactional(readOnly = true)
    public List<WorkforceAccessDtos.Policy> list() {
        PeopleRequestContext.Actor actor = requireGovernor();
        return repository.list(actor.tenantId()).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public List<WorkforceAccessDtos.OrganizationOption> organizations() {
        PeopleRequestContext.Actor actor = requireGovernor();
        return repository.organizations(actor.tenantId());
    }

    @Transactional
    public WorkforceAccessDtos.Policy create(
            WorkforceAccessDtos.CreatePolicyRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = requireGovernor();
        validate(request, actor);
        List<String> fields = normalized(request.fieldGroups(), FIELD_GROUPS, "field group");
        if (!fields.contains("DIRECTORY")) {
            throw invalid("The DIRECTORY field group is required for workforce access.");
        }
        List<String> actions = normalized(request.actionCodes(), ACTIONS, "action");
        UUID policyId = UUID.randomUUID();
        WorkforceAccessPolicyRepository.PolicyRow created;
        try {
            created = repository.create(
                    actor.tenantId(), actor.userId(), policyId, request, fields, actions);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An active workforce boundary already exists for this subject and population.",
                    exception);
        }
        record(
                actor, "workforce.access-policy.created", policyId, correlationId,
                null, snapshot(created), request.justification());
        return summary(created);
    }

    @Transactional
    public WorkforceAccessDtos.Policy revoke(
            UUID policyId,
            WorkforceAccessDtos.RevokePolicyRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = requireGovernor();
        WorkforceAccessPolicyRepository.PolicyRow before = repository
                .find(actor.tenantId(), policyId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        WorkforceAccessPolicyRepository.PolicyRow revoked = repository.revoke(
                actor.tenantId(), actor.userId(), policyId, request.version());
        if (revoked == null) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The workforce boundary changed or is no longer active.");
        }
        record(
                actor, "workforce.access-policy.revoked", policyId, correlationId,
                snapshot(before), snapshot(revoked), request.reason());
        return summary(revoked);
    }

    @Transactional(readOnly = true)
    public Decision require(String action) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        List<WorkforceAccessPolicyRepository.PolicyRow> policies = repository.resolve(
                actor.tenantId(), actor.userId(), actor.roles(), Instant.now());
        boolean hasUserOverride = policies.stream()
                .anyMatch(policy -> "USER".equals(policy.subjectType()));
        List<WorkforceAccessPolicyRepository.PolicyRow> effective = hasUserOverride
                ? policies.stream().filter(policy -> "USER".equals(policy.subjectType())).toList()
                : policies;
        List<WorkforceAccessPolicyRepository.PolicyRow> permitted = effective.stream()
                .filter(policy -> policy.actionCodes().contains(action))
                .toList();
        if (permitted.isEmpty()) {
            denied(actor, action, "NO_ACTIVE_WORKFORCE_BOUNDARY");
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "No active workforce access boundary permits this action.");
        }
        Set<String> fields = new LinkedHashSet<>();
        permitted.forEach(policy -> fields.addAll(policy.fieldGroups()));
        boolean tenantWide = permitted.stream()
                .anyMatch(policy -> "TENANT".equals(policy.populationType()));
        Set<UUID> organizations = tenantWide
                ? Set.of()
                : repository.expandOrganizations(actor.tenantId(), permitted);
        if (!tenantWide && organizations.isEmpty()) {
            denied(actor, action, "EMPTY_TARGET_POPULATION");
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The workforce access boundary resolves to an empty population.");
        }
        return new Decision(tenantWide, organizations, Set.copyOf(fields), action);
    }

    private void validate(
            WorkforceAccessDtos.CreatePolicyRequest request,
            PeopleRequestContext.Actor actor) {
        String subjectRef = request.subjectRef().trim();
        if ("ROLE".equals(request.subjectType())) {
            if (!subjectRef.matches("[A-Z][A-Z0-9_]{1,79}")) {
                throw invalid("The workforce policy role code is invalid.");
            }
        } else {
            try {
                long subjectUserId = Long.parseLong(subjectRef);
                if (subjectUserId <= 0 || subjectUserId == actor.userId()) {
                    throw invalid("Administrators cannot grant a workforce boundary to themselves.");
                }
            } catch (NumberFormatException exception) {
                throw invalid("The workforce policy user identifier is invalid.");
            }
        }
        boolean tenantPopulation = "TENANT".equals(request.populationType());
        if (tenantPopulation != (request.organizationId() == null)) {
            throw invalid("The workforce population reference is invalid.");
        }
        if (request.organizationId() != null
                && !repository.organizationExists(actor.tenantId(), request.organizationId())) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        if (request.validFrom() != null && request.validTo() != null
                && !request.validTo().isAfter(request.validFrom())) {
            throw invalid("The workforce policy validity window is invalid.");
        }
    }

    private List<String> normalized(List<String> values, Set<String> allowed, String label) {
        List<String> normalized = values.stream()
                .map(String::trim)
                .map(String::toUpperCase)
                .distinct()
                .toList();
        if (normalized.isEmpty() || !allowed.containsAll(normalized)) {
            throw invalid("The workforce policy " + label + " is invalid.");
        }
        return normalized;
    }

    private PeopleRequestContext.Actor requireGovernor() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        boolean authorized = actor.permissions().contains(GOVERNANCE_PERMISSION)
                || (actor.permissions().isEmpty()
                    && actor.roles().stream().anyMatch(GOVERNANCE_ROLES::contains));
        if (!authorized) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return actor;
    }

    private WorkforceAccessDtos.Policy summary(WorkforceAccessPolicyRepository.PolicyRow row) {
        return new WorkforceAccessDtos.Policy(
                row.policyId(), row.subjectType(), row.subjectRef(), row.populationType(),
                row.organizationId(), row.organizationName(), row.fieldGroups(), row.actionCodes(),
                row.validFrom(), row.validTo(), row.lifecycleState(), row.justification(), row.version());
    }

    private Map<String, Object> snapshot(WorkforceAccessPolicyRepository.PolicyRow row) {
        return Map.ofEntries(
                Map.entry("subjectType", row.subjectType()),
                Map.entry("subjectRef", row.subjectRef()),
                Map.entry("populationType", row.populationType()),
                Map.entry("organizationId", row.organizationId() == null ? "" : row.organizationId()),
                Map.entry("fieldGroups", row.fieldGroups()),
                Map.entry("actionCodes", row.actionCodes()),
                Map.entry("lifecycleState", row.lifecycleState()),
                Map.entry("version", row.version()));
    }

    private void record(
            PeopleRequestContext.Actor actor,
            String action,
            UUID policyId,
            String correlationId,
            Map<String, Object> before,
            Map<String, Object> after,
            String reason) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("workforce-access")
                .targetType("WORKFORCE_ACCESS_POLICY")
                .targetId(policyId.toString())
                .reason(reason)
                .correlationId(correlationId)
                .beforeState(before)
                .afterState(after)
                .retentionClass("EXTENDED")
                .build());
    }

    private void denied(PeopleRequestContext.Actor actor, String action, String reason) {
        deniedAudit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("POLICY_DENIED")
                .action("workforce.access.denied")
                .outcome("DENIED")
                .severity("HIGH")
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("workforce-access")
                .targetType("WORKFORCE_ACTION")
                .targetId(action)
                .reason(reason)
                .metadata(Map.of("requestedAction", action))
                .retentionClass("EXTENDED")
                .build());
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    public record Decision(
            boolean tenantWide,
            Set<UUID> organizationIds,
            Set<String> fieldGroups,
            String action) {

        public boolean includes(UUID organizationId) {
            return tenantWide || (organizationId != null && organizationIds.contains(organizationId));
        }

        public boolean field(String fieldGroup) {
            return fieldGroups.contains(fieldGroup);
        }

        public String fingerprint() {
            return tenantWide + "|"
                    + organizationIds.stream().map(UUID::toString).sorted().toList() + "|"
                    + fieldGroups.stream().sorted().toList() + "|" + action;
        }
    }
}
