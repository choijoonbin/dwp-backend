package com.dwp.platform.contracts.hris.identity.v1;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1.Code.*;

/** Unwired structural/policy pilot. Actual current-proof and native owner adapters remain OPEN. */
public final class GuardedSelfContextPortV1 implements SelfContextPortV1 {
    private static final Duration MAX_LEASE = Duration.ofSeconds(30);
    private static final int MAX_CONTEXTS = 100;

    private final SelfContextPurposeV1.Audience audience;
    private final Clock clock;
    private final SelfContextOwnerPortsV1.AuthorityVerifier verifier;
    private final SelfContextOwnerPortsV1.AuthBindingProvider auth;
    private final SelfContextOwnerPortsV1.NativeContextProvider people;

    private GuardedSelfContextPortV1(SelfContextPurposeV1.Audience audience, Clock clock,
                             SelfContextOwnerPortsV1.AuthorityVerifier verifier,
                             SelfContextOwnerPortsV1.AuthBindingProvider auth,
                             SelfContextOwnerPortsV1.NativeContextProvider people) {
        this.audience = audience;
        this.clock = clock;
        this.verifier = verifier;
        this.auth = auth;
        this.people = people;
    }

    /** Owner composition boundary only. Adapter authentication/production registration remains OPEN. */
    public static SelfContextPortV1 guarded(SelfContextPurposeV1.Audience audience, Clock clock,
                                            SelfContextOwnerPortsV1.AuthorityVerifier verifier,
                                            SelfContextOwnerPortsV1.AuthBindingProvider auth,
                                            SelfContextOwnerPortsV1.NativeContextProvider people) {
        return new GuardedSelfContextPortV1(audience, clock, verifier, auth, people);
    }

    @Override
    public SelfContextResolutionV1 resolve(SelfContextQueryV1 query) {
        require(verifier != null && auth != null && people != null && clock != null && audience != null,
                ADAPTER_UNAVAILABLE);
        Instant now = clock.instant();
        require(query != null && query.purpose() != null && query.asOf() != null
                && !query.asOf().isAfter(now) && civilDateSupported(query.asOf())
                && query.purpose().audience() == audience, QUERY_INVALID);
        require(query.selector() == null || selectorValid(query.selector()), QUERY_INVALID);
        SelfContextAuthorityV1 authority = ownerCall(() -> verifier.verifyCurrent(query, audience, now));
        Instant verifiedNow = captureAfter(now);
        checkAuthority(authority, query, verifiedNow);
        AuthPersonBindingV1 binding = ownerCall(() -> auth.loadCurrent(
                SelfContextOwnerPortsV1.authLookup(authority, verifiedNow)));
        Instant boundNow = captureAfter(verifiedNow);
        checkAuthority(authority, query, boundNow);
        checkBinding(binding, authority, boundNow);
        NativeSelfContextSetV1 nativeSet = ownerCall(() -> people.loadComplete(
                SelfContextOwnerPortsV1.peopleLookup(binding, authority, query, boundNow)));
        List<NativeSelfContextSetV1.EmploymentContext> eligible = checkNative(nativeSet, binding, authority, query);
        require(!eligible.isEmpty(), SELF_SCOPE_UNRESOLVED);
        Instant issuanceNow = captureAfter(boundNow);
        checkAuthority(authority, query, issuanceNow);
        checkBinding(binding, authority, issuanceNow);
        if (query.selector() != null) {
            NativeSelfContextSetV1.EmploymentContext selected = eligible.stream()
                    .filter(context -> context.selector().equals(query.selector())).findFirst().orElse(null);
            require(selected != null, SELF_CONTEXT_FORBIDDEN);
            return SelfContextResolutionV1.selected(binding, authority, nativeSet, selected, issuanceNow);
        }
        if (eligible.size() == 1) {
            return SelfContextResolutionV1.selected(binding, authority, nativeSet, eligible.getFirst(), issuanceNow);
        }
        return SelfContextResolutionV1.selectionRequired(eligible.stream()
                .map(NativeSelfContextSetV1.EmploymentContext::selector).toList());
    }

    /** Owner IO consumes real time; never validate a post-read capture against a pre-read clock. */
    private Instant captureAfter(Instant previous) {
        Instant current = clock.instant();
        require(current != null && !current.isBefore(previous), AUTHORITY_INVALID);
        return current;
    }

    private void checkAuthority(SelfContextAuthorityV1 value, SelfContextQueryV1 query, Instant now) {
        require(value != null && value.tenantId() > 0 && value.userId() > 0
                && uuid(value.principalPublicId()) && uuid(value.personPublicId())
                && value.expectedUserRowVersion() >= 0 && value.expectedAccessRevision() >= 0
                && value.permissionDecisionRevision() >= 0 && value.audience() == audience
                && value.purpose() == query.purpose() && lease(value.issuedAt(), value.expiresAt(), now)
                && policy(value.effectivePolicy()), AUTHORITY_INVALID);
        require(value.appEntitled(), APP_ENTITLEMENT_REQUIRED);
        require(value.separationOfDutiesAllowed(), SEPARATION_OF_DUTIES_DENIED);
    }

    private void checkBinding(AuthPersonBindingV1 value, SelfContextAuthorityV1 authority, Instant now) {
        require(value != null && value.tenantId() == authority.tenantId() && value.userId() == authority.userId()
                && authority.principalPublicId().equals(value.principalPublicId())
                && authority.personPublicId().equals(value.personPublicId())
                && value.identityPlane() == AuthPersonBindingV1.IdentityPlane.TENANT
                && value.status() != null && value.userRowVersion() >= 0 && value.accessRevision() >= 0,
                AUTH_BINDING_INVALID);
        require(value.status() == AuthPersonBindingV1.Status.ACTIVE, AUTH_BINDING_REVOKED);
        require(value.userRowVersion() == authority.expectedUserRowVersion()
                && value.accessRevision() == authority.expectedAccessRevision()
                && lease(value.capturedAt(), value.expiresAt(), now), AUTH_BINDING_STALE);
    }

    private List<NativeSelfContextSetV1.EmploymentContext> checkNative(NativeSelfContextSetV1 value,
            AuthPersonBindingV1 binding, SelfContextAuthorityV1 authority, SelfContextQueryV1 query) {
        require(value != null && value.tenantId() == binding.tenantId()
                && binding.personPublicId().equals(value.personPublicId()) && value.personVersion() >= 0
                && value.personState() != null
                && query.asOf().equals(value.asOf()) && value.complete() && value.contexts() != null
                && value.contexts().size() <= MAX_CONTEXTS, OWNER_RESPONSE_INVALID);
        Set<SelfContextSelectorV1> selectors = new HashSet<>();
        Map<UUID, NativeSelfContextSetV1.Worker> workers = new HashMap<>();
        Map<UUID, NativeSelfContextSetV1.WorkRelationship> relationships = new HashMap<>();
        Map<UUID, NativeSelfContextSetV1.Assignment> assignments = new HashMap<>();
        for (NativeSelfContextSetV1.EmploymentContext context : value.contexts()) {
            require(context != null && context.worker() != null && context.relationship() != null
                    && context.assignment() != null, OWNER_RESPONSE_INVALID);
            var worker = context.worker();
            var relationship = context.relationship();
            var assignment = context.assignment();
            require(uuid(worker.publicId()) && binding.personPublicId().equals(worker.personPublicId())
                    && worker.version() >= 0 && worker.status() != null && uuid(relationship.publicId())
                    && worker.publicId().equals(relationship.workerPublicId())
                    && uuid(relationship.legalEmployerPublicId()) && relationship.version() >= 0
                    && uuid(assignment.publicId()) && relationship.publicId().equals(assignment.workRelationshipPublicId())
                    && assignment.version() >= 0 && assignment.status() != null && assignment.workZone() != null
                    && intervalValid(relationship.startDate(), relationship.endDate())
                    && intervalValid(assignment.effectiveStartDate(), assignment.effectiveEndDate())
                    && selectors.add(context.selector())
                    && consistent(workers, worker.publicId(), worker)
                    && consistent(relationships, relationship.publicId(), relationship)
                    && consistent(assignments, assignment.publicId(), assignment), OWNER_RESPONSE_INVALID);
        }
        if (!authority.effectivePolicy().allowedPersonStates().contains(value.personState())) {
            return List.of();
        }
        return value.contexts().stream().filter(context -> eligible(context, authority.effectivePolicy(), query.asOf()))
                .toList();
    }

    private boolean eligible(NativeSelfContextSetV1.EmploymentContext context,
                              SelfContextAuthorityV1.EffectivePolicy policy, Instant asOf) {
        LocalDate date;
        try {
            date = asOf.atZone(context.assignment().workZone()).toLocalDate();
        } catch (DateTimeException invalidZoneDate) {
            throw new SelfContextContractExceptionV1(OWNER_RESPONSE_INVALID);
        }
        return policy.allowedWorkerStatuses().contains(context.worker().status())
                && policy.allowedAssignmentStatuses().contains(context.assignment().status())
                && effective(date, context.relationship().startDate(), context.relationship().endDate())
                && effective(date, context.assignment().effectiveStartDate(), context.assignment().effectiveEndDate());
    }

    private boolean policy(SelfContextAuthorityV1.EffectivePolicy policy) {
        return policy != null && policy.reference() != null
                && policy.reference().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}") && policy.version() >= 0
                && policy.allowedPersonStates() != null && !policy.allowedPersonStates().isEmpty()
                && statuses(policy.allowedWorkerStatuses()) && statuses(policy.allowedAssignmentStatuses());
    }

    private <T> boolean consistent(Map<UUID, T> records, UUID id, T value) {
        T prior = records.putIfAbsent(id, value);
        return prior == null || prior.equals(value);
    }

    private boolean statuses(Set<String> statuses) {
        return statuses != null && !statuses.isEmpty() && statuses.size() <= 32
                && statuses.stream().allMatch(status -> status.matches("[A-Z][A-Z0-9_]{0,31}"));
    }

    private boolean lease(Instant issued, Instant expires, Instant now) {
        return issued != null && expires != null && !issued.isAfter(now) && expires.isAfter(now)
                && expires.isAfter(issued) && !issued.isBefore(now.minus(MAX_LEASE))
                && !expires.isAfter(issued.plus(MAX_LEASE));
    }

    private boolean intervalValid(LocalDate start, LocalDate end) {
        return start != null && (end == null || !end.isBefore(start));
    }

    private boolean effective(LocalDate date, LocalDate start, LocalDate end) {
        return !date.isBefore(start) && (end == null || !date.isAfter(end));
    }

    private boolean selectorValid(SelfContextSelectorV1 selector) {
        return uuid(selector.workerPublicId()) && uuid(selector.workRelationshipPublicId()) && uuid(selector.assignmentPublicId());
    }

    private boolean civilDateSupported(Instant asOf) {
        try {
            asOf.atOffset(ZoneOffset.UTC);
            return true;
        } catch (DateTimeException outsideCivilDateRange) {
            return false;
        }
    }

    private boolean uuid(UUID value) {
        return value != null && (value.getMostSignificantBits() != 0 || value.getLeastSignificantBits() != 0);
    }

    private <T> T ownerCall(Supplier<T> action) {
        try {
            return action.get();
        } catch (SelfContextContractExceptionV1 rejected) {
            throw rejected;
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new SelfContextContractExceptionV1(OWNER_RESPONSE_INVALID);
        } catch (RuntimeException unavailable) {
            throw new SelfContextContractExceptionV1(OWNER_UNAVAILABLE);
        }
    }

    private void require(boolean condition, SelfContextContractExceptionV1.Code code) {
        if (!condition) {
            throw new SelfContextContractExceptionV1(code);
        }
    }
}
