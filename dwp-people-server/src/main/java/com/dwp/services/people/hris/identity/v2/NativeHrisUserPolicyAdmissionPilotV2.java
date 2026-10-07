package com.dwp.services.people.hris.identity.v2;

import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationPortsV2.PeopleLookup;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.*;
import com.dwp.platform.contracts.hris.identity.v2.NativeHrisCurrentRoleEvidenceV2;
import com.dwp.platform.contracts.hris.identity.v2.NativeHrisTargetReadEvidencePortsV2.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import static com.dwp.services.people.hris.identity.v2.NativeHrisTargetPolicyInputsV2.*;
import static com.dwp.services.people.hris.identity.v2.NativeHrisTargetPolicyInputsV2.Code.*;

/**
 * UNWIRED native USER/READ admission subset. Not a signed current proof or complete
 * People PEP: production Gateway/Auth-role transports, purpose publication, field
 * projection and command/dynamic-SoD transaction fencing remain OPEN.
 */
public final class NativeHrisUserPolicyAdmissionPilotV2 {
    private static final Set<String> GROUPS = Set.of("DIRECTORY", "WORKER_IDENTIFIERS", "EMPLOYMENT", "JOB_GRADE");
    private final ReadOperation operation;
    private final CurrentGatewayContextProvider gateway;
    private final CurrentAuthRoleEvidenceProvider roles;
    private final EmploymentDatePolicyProvider dates;
    private final InputsProvider inputs;
    private final Clock clock;

    public NativeHrisUserPolicyAdmissionPilotV2(ReadOperation operation,
            CurrentGatewayContextProvider gateway, CurrentAuthRoleEvidenceProvider roles,
            EmploymentDatePolicyProvider dates, InputsProvider inputs, Clock clock) {
        this.operation = operation; this.gateway = gateway; this.roles = roles;
        this.dates = dates; this.inputs = inputs; this.clock = clock;
    }
    public ReadAdmission read(PeopleLookup lookup) {
        if (operation == null || gateway == null || roles == null || inputs == null || clock == null)
            throw new Rejected(UNAVAILABLE); // Missing adapters: zero source/Clock/SQL calls.
        try {
            context(lookup);
            if (lookup.selector().kind() == TargetKind.EMPLOYMENT && dates == null)
                throw new Rejected(DATE_POLICY_UNAVAILABLE);
            Instant first = now(null);
            GatewayContext initialGateway = gateway.loadCurrent(lookup);
            Instant atGateway = now(first); gateway(initialGateway, lookup, first, atGateway);
            NativeHrisCurrentRoleEvidenceV2 initialRoles = roles.loadCurrent(lookup);
            Instant atRoles = now(atGateway); roles(initialRoles, lookup, atGateway, atRoles);
            EmploymentDatePolicy initialDate = date(lookup, atRoles);
            Instant atDate = now(atRoles); if (initialDate != null) date(initialDate, lookup, atRoles, atRoles, atDate);
            Inputs initial = inputs.read(lookup, roleCodes(initialRoles), atDate,
                    initialDate == null ? null : initialDate.localDate());
            Instant atInputs = now(atDate);
            List<Policy> matched = admission(initial, lookup, initialDate == null ? null : initialDate.localDate(), atDate);
            GatewayContext currentGateway = gateway.loadCurrent(lookup);
            Instant atCurrentGateway = now(atInputs); gateway(currentGateway, lookup, atInputs, atCurrentGateway);
            NativeHrisCurrentRoleEvidenceV2 currentRoles = roles.loadCurrent(lookup);
            Instant atCurrentRoles = now(atCurrentGateway); roles(currentRoles, lookup, atCurrentGateway, atCurrentRoles);
            EmploymentDatePolicy currentDate = date(lookup, atCurrentRoles);
            Instant atCurrentDate = now(atCurrentRoles);
            if (currentDate != null) date(currentDate, lookup, atCurrentRoles, atCurrentRoles, atCurrentDate);
            Inputs current = inputs.read(lookup, roleCodes(currentRoles), atDate,
                    currentDate == null ? null : currentDate.localDate());
            Instant issued = now(atCurrentDate);
            if (!gatewayFacts(initialGateway).equals(gatewayFacts(currentGateway))
                    || !roleFacts(initialRoles).equals(roleFacts(currentRoles))
                    || !Objects.equals(dateFacts(initialDate), dateFacts(currentDate)) || !initial.equals(current))
                throw new Rejected(SOURCE_CHANGED);
            lease(initialGateway.capturedAt(), initialGateway.expiresAt(), first, issued);
            lease(initialRoles.capturedAt(), initialRoles.expiresAt(), atGateway, issued);
            if (initialDate != null) lease(initialDate.capturedAt(), initialDate.expiresAt(), atRoles, issued);
            lease(currentGateway.capturedAt(), currentGateway.expiresAt(), atInputs, issued);
            lease(currentRoles.capturedAt(), currentRoles.expiresAt(), atCurrentGateway, issued);
            if (currentDate != null) lease(currentDate.capturedAt(), currentDate.expiresAt(), atCurrentRoles, issued);
            if (!lookup.authority().expiresAt().isAfter(issued)) throw new Rejected(STALE);
            if (!lookup.authority().actor().expiresAt().isAfter(issued)) throw new Rejected(STALE);
            for (var source : java.util.stream.Stream.concat(initialRoles.roles().stream(), currentRoles.roles().stream()).toList())
                if (source.validTo() != null && !source.validTo().isAfter(issued)) throw new Rejected(STALE);
            for (var policy : matched) if (policy.validTo() != null && !policy.validTo().isAfter(issued)) throw new Rejected(STALE);
            Instant expiry = java.util.stream.Stream.of(initialGateway.expiresAt(), initialRoles.expiresAt(),
                    initialDate == null ? null : initialDate.expiresAt(), currentGateway.expiresAt(),
                    currentRoles.expiresAt(), currentDate == null ? null : currentDate.expiresAt(),
                    lookup.authority().expiresAt(), lookup.authority().actor().expiresAt()).filter(Objects::nonNull).min(Instant::compareTo).orElseThrow();
            expiry = java.util.stream.Stream.concat(java.util.stream.Stream.of(expiry),
                    matched.stream().map(Policy::validTo).filter(Objects::nonNull)).min(Instant::compareTo).orElseThrow();
            expiry = java.util.stream.Stream.concat(java.util.stream.Stream.of(expiry),
                    java.util.stream.Stream.concat(initialRoles.roles().stream(), currentRoles.roles().stream())
                            .map(NativeHrisCurrentRoleEvidenceV2.RoleSource::validTo).filter(Objects::nonNull)).min(Instant::compareTo).orElseThrow();
            if (!expiry.isAfter(issued)) throw new Rejected(STALE);
            return new ReadAdmission(initial.target(), operation.requirements().fieldPaths(), matched, initial, issued, expiry);
        } catch (Rejected rejected) { throw new Rejected(rejected.code());
        } catch (RuntimeException providerFailure) { throw new Rejected(UNAVAILABLE); }
    }

    private void context(PeopleLookup lookup) {
        if (lookup == null || lookup.authority() == null || lookup.selector() == null
                || !Objects.equals(operation.requirements(), lookup.authority().requirements())) throw new Rejected(CONTEXT_INVALID);
        var requirement = operation.requirements(); var selector = lookup.selector();
        if (requirement.mutation() || requirement.targetKind() != selector.kind()
                || selector.personPublicId() == null || lookup.authority().decision() != Decision.ALLOWED
                || !lookup.authority().appEntitled() || !lookup.authority().staticSodAllowed()
                || !lookup.authority().grantedPermissions().containsAll(requirement.requiredPermissions())
                || !lookup.authority().grantedAtomicDuties().containsAll(requirement.requiredAtomicDuties())
                || requirement.fieldPaths().isEmpty() || !operation.fieldGroups().keySet().equals(requirement.fieldPaths())
                || !GROUPS.containsAll(operation.fieldGroups().values()) || operation.allowedPersonStates().isEmpty()
                || !Set.of("ACTIVE", "INACTIVE").containsAll(operation.allowedPersonStates())) throw new Rejected(CONTEXT_INVALID);
        if (requirement.selection() == Selection.SELF
                && !Objects.equals(lookup.authority().actor().personPublicId(), selector.personPublicId())) throw new Rejected(CONTEXT_INVALID);
        boolean hasAll = selector.workerPublicId() != null && selector.workRelationshipPublicId() != null && selector.assignmentPublicId() != null;
        boolean hasAny = selector.workerPublicId() != null || selector.workRelationshipPublicId() != null || selector.assignmentPublicId() != null;
        if (selector.kind() == TargetKind.PERSON ? hasAny : !hasAll) throw new Rejected(CONTEXT_INVALID);
    }
    private Instant now(Instant previous) {
        Instant value;
        try { value = clock.instant(); } catch (RuntimeException unavailable) { throw new Rejected(CLOCK_INVALID); }
        if (value == null || (previous != null && value.isBefore(previous))) throw new Rejected(CLOCK_INVALID);
        return value;
    }
    private void lease(Instant captured, Instant expiry, Instant minimum, Instant current) {
        if (captured == null || expiry == null || captured.isBefore(minimum) || captured.isAfter(current)
                || !expiry.isAfter(current) || !expiry.isAfter(captured)
                || Duration.between(captured, expiry).compareTo(Duration.ofSeconds(30)) > 0) throw new Rejected(STALE);
    }
    private void gateway(GatewayContext context, PeopleLookup lookup, Instant minimum, Instant now) {
        var auth = lookup.authority(); var actor = auth.actor();
        if (context == null || !Objects.equals(context.requirements(), operation.requirements())
                || context.tenantId() != actor.tenantId() || context.userId() != actor.userId()
                || !Objects.equals(context.authRevision(), auth.authRevision().value())
                || !Objects.equals(context.policyRevision(), auth.policyRevision().value())
                || !Objects.equals(context.contextKey(), auth.contextKey().value())
                || !Objects.equals(context.decisionRevision(), auth.decisionRevision().value())
                || !Objects.equals(context.selectedScopeKey(), auth.selectedScopeKey())) throw new Rejected(CONTEXT_INVALID);
        lease(context.capturedAt(), context.expiresAt(), minimum, now);
    }
    private void roles(NativeHrisCurrentRoleEvidenceV2 evidence, PeopleLookup lookup, Instant minimum, Instant now) {
        var actor = lookup.authority().actor();
        if (evidence == null || !evidence.complete() || evidence.roles() == null || evidence.roles().size() > 100
                || evidence.tenantId() != actor.tenantId() || evidence.userId() != actor.userId()
                || !Objects.equals(evidence.principalPublicId(), actor.principalPublicId())
                || evidence.userRowVersion() != actor.userRowVersion() || evidence.accessRevision() != actor.accessRevision()
                || !Objects.equals(evidence.authRevision(), lookup.authority().authRevision().value())) throw new Rejected(ROLE_EVIDENCE_INVALID);
        var roleIdentities = new HashMap<Long, List<Object>>(); var codeIdentities = new HashMap<String, List<Object>>();
        var sources = new HashSet<List<Object>>();
        for (var role : evidence.roles()) {
            if (role.roleId() <= 0 || role.roleCode() == null || !role.roleCode().matches("[A-Z][A-Z0-9_]{1,79}")
                    || role.roleVersion() < 0 || role.sourceKind() == null || role.sourceStamp() == null
                    || role.sourceStamp().isAfter(now) || (role.validFrom() != null && role.validFrom().isAfter(now))
                    || (role.validTo() != null && !role.validTo().isAfter(now))) throw new Rejected(ROLE_EVIDENCE_INVALID);
            var identity = List.<Object>of(role.roleCode(), role.roleVersion());
            var prior = roleIdentities.putIfAbsent(role.roleId(), identity);
            if (prior != null && !prior.equals(identity)) throw new Rejected(ROLE_EVIDENCE_INVALID);
            var codePrior = codeIdentities.putIfAbsent(role.roleCode(), List.of(role.roleId(), role.roleVersion()));
            if (codePrior != null && !codePrior.equals(List.of(role.roleId(), role.roleVersion()))) throw new Rejected(ROLE_EVIDENCE_INVALID);
            boolean group = role.sourceKind() == NativeHrisCurrentRoleEvidenceV2.SourceKind.GROUP;
            boolean privileged = role.sourceKind() == NativeHrisCurrentRoleEvidenceV2.SourceKind.PRIVILEGED;
            if (role.sourceKind() == NativeHrisCurrentRoleEvidenceV2.SourceKind.DIRECT
                    && (role.validFrom() != null || role.validTo() != null)) throw new Rejected(ROLE_EVIDENCE_INVALID);
            if (privileged ? role.sourceId() != null || role.sourcePublicId() == null || role.sourceVersion() != null
                    || role.validFrom() == null || role.validTo() == null
                    : role.sourceId() == null || role.sourceId() <= 0 || role.sourcePublicId() != null)
                throw new Rejected(ROLE_EVIDENCE_INVALID);
            if (group ? role.sourceVersion() == null || role.sourceVersion() < 0 || role.groupId() == null
                    || role.groupId() <= 0 || role.groupVersion() == null || role.groupVersion() < 0
                    || role.membershipStamp() == null || role.membershipStamp().isAfter(now)
                    : role.groupId() != null || role.groupVersion() != null || role.membershipStamp() != null
                    || role.sourceVersion() != null) throw new Rejected(ROLE_EVIDENCE_INVALID);
            Object sourceId = privileged ? role.sourcePublicId() : role.sourceId();
            if (!sources.add(List.of(role.sourceKind(), sourceId))) throw new Rejected(ROLE_EVIDENCE_INVALID);
        }
        lease(evidence.capturedAt(), evidence.expiresAt(), minimum, now);
    }
    private EmploymentDatePolicy date(PeopleLookup lookup, Instant asOf) {
        return lookup.selector().kind() == TargetKind.PERSON ? null : dates.loadCurrent(lookup, asOf);
    }
    private void date(EmploymentDatePolicy date, PeopleLookup lookup, Instant asOf, Instant minimum, Instant now) {
        var req = operation.requirements();
        if (date == null || !Objects.equals(date.operationId(), req.operationId()) || !Objects.equals(date.purpose(), req.purpose())
                || !Objects.equals(date.audience(), req.audience()) || !Objects.equals(date.asOf(), asOf)
                || date.localDate() == null || date.policyRef() == null || date.policyRef().isBlank()
                || date.revision() == null || date.revision().isBlank()) throw new Rejected(DATE_POLICY_INVALID);
        lease(date.capturedAt(), date.expiresAt(), minimum, now);
    }
    private List<Policy> admission(Inputs facts, PeopleLookup lookup, LocalDate date, Instant asOf) {
        if (facts == null || facts.target() == null) throw new Rejected(NATIVE_INVALID);
        var target = facts.target(); var selector = lookup.selector();
        if (target.kind() != selector.kind() || target.tenantId() != lookup.authority().actor().tenantId()
                || !Objects.equals(target.personPublicId(), selector.personPublicId()) || target.personVersion() < 0
                || !operation.allowedPersonStates().contains(target.personState())) throw new Rejected(NATIVE_INVALID);
        if (target.kind() == TargetKind.EMPLOYMENT) {
            if (!Objects.equals(target.workerPublicId(), selector.workerPublicId())
                    || !Objects.equals(target.workRelationshipPublicId(), selector.workRelationshipPublicId())
                    || !Objects.equals(target.assignmentPublicId(), selector.assignmentPublicId())
                    || !Objects.equals(target.workerPersonPublicId(), target.personPublicId())
                    || !Objects.equals(target.relationshipWorkerPublicId(), target.workerPublicId())
                    || !Objects.equals(target.assignmentRelationshipPublicId(), target.workRelationshipPublicId())
                    || target.workerVersion() == null || target.workerVersion() < 0
                    || target.workRelationshipVersion() == null || target.workRelationshipVersion() < 0
                    || target.assignmentVersion() == null || target.assignmentVersion() < 0
                    || !Set.of("ACTIVE", "LEAVE").contains(facts.workerState())
                    || !Set.of("ACTIVE", "SUSPENDED", "PENDING").contains(facts.assignmentState())
                    || !effective(date, facts.relationshipStart(), facts.relationshipEnd())
                    || !effective(date, facts.assignmentStart(), facts.assignmentEnd())) throw new Rejected(NATIVE_INVALID);
        } else if (target.workerPublicId() != null || target.workerPersonPublicId() != null || target.workerVersion() != null
                || target.workRelationshipPublicId() != null || target.relationshipWorkerPublicId() != null
                || target.workRelationshipVersion() != null || target.assignmentPublicId() != null
                || target.assignmentRelationshipPublicId() != null || target.assignmentVersion() != null
                || facts.organizationPublicId() != null || !facts.ancestors().isEmpty()) throw new Rejected(NATIVE_INVALID);
        var ancestors = new HashMap<UUID, Organization>();
        for (var org : facts.ancestors()) {
            if (org.publicId() == null || org.version() < 0 || !"ACTIVE".equals(org.state())
                    || ancestors.putIfAbsent(org.publicId(), org) != null) throw new Rejected(NATIVE_INVALID);
        }
        var visited = new HashSet<UUID>(); UUID cursor = facts.organizationPublicId();
        while (cursor != null) {
            if (!visited.add(cursor) || !ancestors.containsKey(cursor)) throw new Rejected(NATIVE_INVALID);
            cursor = ancestors.get(cursor).parentPublicId();
        }
        if (visited.size() != ancestors.size()) throw new Rejected(NATIVE_INVALID);
        var policyIds = new HashSet<UUID>();
        for (var policy : facts.policies()) {
            if (policy.publicId() == null || !policyIds.add(policy.publicId()) || policy.tenantId() != target.tenantId()
                    || policy.version() < 0 || !"ACTIVE".equals(policy.state()) || !GROUPS.containsAll(policy.fieldGroups())
                    || policy.fieldGroups().isEmpty() || !Set.of("READ", "EXPORT").containsAll(policy.actions())
                    || policy.actions().isEmpty() || (policy.validFrom() != null && policy.validFrom().isAfter(asOf))
                    || (policy.validTo() != null && !policy.validTo().isAfter(asOf))) throw new Rejected(NATIVE_INVALID);
            if ("ROLE".equals(policy.subjectType())) throw new Rejected(UNSUPPORTED_POLICY);
            if (!"USER".equals(policy.subjectType()) || !Long.toString(lookup.authority().actor().userId()).equals(policy.subjectRef()))
                throw new Rejected(NATIVE_INVALID);
            if (!Set.of("TENANT", "ORG_UNIT", "ORG_TREE").contains(policy.populationType())
                    || ("TENANT".equals(policy.populationType()) ? policy.organizationPublicId() != null
                    : policy.organizationPublicId() == null || !policy.organizationResolved())) throw new Rejected(NATIVE_INVALID);
        }
        List<Policy> matched = facts.policies().stream().filter(p -> p.actions().contains("READ"))
                .filter(p -> "TENANT".equals(p.populationType()) || (target.kind() == TargetKind.EMPLOYMENT
                        && ("ORG_UNIT".equals(p.populationType()) ? Objects.equals(p.organizationPublicId(), facts.organizationPublicId())
                        : ancestors.containsKey(p.organizationPublicId())))).toList();
        var groups = new HashSet<String>(); matched.forEach(policy -> groups.addAll(policy.fieldGroups()));
        if (matched.isEmpty() || !groups.containsAll(operation.fieldGroups().values())) throw new Rejected(POLICY_DENIED);
        return matched;
    }
    private boolean effective(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && from != null && !from.isAfter(date) && (to == null || !to.isBefore(date));
    }
    private Set<String> roleCodes(NativeHrisCurrentRoleEvidenceV2 roles) {
        return roles.roles().stream().map(NativeHrisCurrentRoleEvidenceV2.RoleSource::roleCode).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private Object gatewayFacts(GatewayContext g) {
        return List.of(g.requirements(), g.tenantId(), g.userId(), g.authRevision(), g.policyRevision(), g.contextKey(), g.decisionRevision(), g.selectedScopeKey());
    }
    private Object roleFacts(NativeHrisCurrentRoleEvidenceV2 r) {
        return List.of(r.tenantId(), r.userId(), r.principalPublicId(), r.userRowVersion(), r.accessRevision(), r.authRevision(), Set.copyOf(r.roles()));
    }
    private Object dateFacts(EmploymentDatePolicy d) {
        return d == null ? null : List.of(d.operationId(), d.purpose(), d.audience(), d.policyRef(), d.revision(), d.localDate());
    }
}
