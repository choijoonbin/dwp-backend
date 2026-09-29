package com.dwp.platform.contracts.hris.identity.v2;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import com.dwp.platform.contracts.hris.identity.v1.AuthPersonBindingV1;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.*;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationPortsV2.*;
import static com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationExceptionV2.Code.*;

/**
 * UNWIRED fail-closed neutral bridge. Raw owner carriers are obtainable only from configured
 * trusted server providers, not public authorize arguments. Does not evaluate RBAC itself.
 * Sequential refetch is bounded composition, NOT cross-database atomic revocation/transaction PEP.
 */
public final class GuardedCurrentHrisAuthorizationPortV2 {
    private static final Duration MAX_LEASE = Duration.ofSeconds(30);
    private final Requirements requirements;
    private final CurrentInvocationProvider invocationProvider;
    private final CurrentDwpAuthorityProvider dwpProvider;
    private final CurrentPeopleAuthorityProvider peopleProvider;
    private final Clock clock;

    public GuardedCurrentHrisAuthorizationPortV2(Requirements requirements,
            CurrentInvocationProvider invocationProvider, CurrentDwpAuthorityProvider dwpProvider,
            CurrentPeopleAuthorityProvider peopleProvider, Clock clock) {
        this.requirements = requirements; this.invocationProvider = invocationProvider;
        this.dwpProvider = dwpProvider; this.peopleProvider = peopleProvider; this.clock = clock;
    }

    public VerifiedCurrentHrisAuthorizationV2 authorize(TargetSelector selector) {
        if (requirements == null || invocationProvider == null || dwpProvider == null
                || peopleProvider == null || clock == null) throw deny(MISSING_ADAPTER);
        requirements();
        Instant initial = now(null);
        Invocation invocation = owner(invocationProvider::current);
        Instant invokedNow = now(initial);
        invocation(invocation, initial, invokedNow);
        TargetSelector selected = selector(invocation, selector);
        DwpAuthoritySnapshot dwp = owner(() -> dwpProvider.loadCurrent(
                CurrentHrisAuthorizationPortsV2.dwp(invocation, requirements, invokedNow)));
        Instant authorizedNow = now(invokedNow);
        dwp(invocation, dwp, invokedNow, authorizedNow);
        PeopleAuthoritySnapshot people = owner(() -> peopleProvider.loadCurrent(
                CurrentHrisAuthorizationPortsV2.people(dwp, selected, authorizedNow)));
        Instant peopleNow = now(authorizedNow);
        people(dwp, selected, people, authorizedNow, peopleNow);
        DwpAuthoritySnapshot refreshedDwp = owner(() -> dwpProvider.loadCurrent(
                CurrentHrisAuthorizationPortsV2.dwp(invocation, requirements, peopleNow)));
        Instant refreshedDwpNow = now(peopleNow);
        dwp(invocation, refreshedDwp, peopleNow, refreshedDwpNow);
        if (!sameDwp(dwp, refreshedDwp)) throw deny(AUTHORITY_CHANGED);
        PeopleAuthoritySnapshot refreshedPeople = owner(() -> peopleProvider.loadCurrent(
                CurrentHrisAuthorizationPortsV2.people(refreshedDwp, selected, refreshedDwpNow)));
        Instant issuedNow = now(refreshedDwpNow);
        people(refreshedDwp, selected, refreshedPeople, refreshedDwpNow, issuedNow);
        if (!samePeople(people, refreshedPeople)) throw deny(TARGET_CHANGED);
        lease(invocation.capturedAt(), invocation.expiresAt(), initial, issuedNow);
        lease(dwp.capturedAt(), dwp.expiresAt(), invokedNow, issuedNow);
        lease(dwp.actor().capturedAt(), dwp.actor().expiresAt(), invokedNow, issuedNow);
        lease(people.capturedAt(), people.expiresAt(), authorizedNow, issuedNow);
        Instant expiry = minimum(invocation.expiresAt(), dwp.expiresAt(), dwp.actor().expiresAt(),
                people.expiresAt(), refreshedDwp.expiresAt(), refreshedDwp.actor().expiresAt(),
                refreshedPeople.expiresAt());
        return VerifiedCurrentHrisAuthorizationV2.mint(requirements, refreshedDwp, refreshedPeople, issuedNow, expiry);
    }

    private void requirements() {
        if (!text(requirements.operationId()) || !text(requirements.productKey())
                || !text(requirements.surfaceKey()) || !text(requirements.routeContractKey())
                || !text(requirements.purpose()) || !text(requirements.audience())
                || requirements.plane() == null || requirements.accessMode() == null
                || requirements.selection() == null || requirements.targetKind() == null
                || !strings(requirements.requiredPermissions(), false)
                || !strings(requirements.requiredAtomicDuties(), true)
                || !strings(requirements.fieldPaths(), false)) throw deny(AUTHORITY_MISMATCH);
    }
    private void invocation(Invocation value, Instant before, Instant now) {
        if (value == null || value.tenantId() <= 0 || value.userId() <= 0
                || !uuid(value.principalPublicId()) || (value.personPublicId() != null && !uuid(value.personPublicId()))
                || value.expectedUserRowVersion() < 0 || value.expectedAccessRevision() < 0
                || !requirements.operationId().equals(value.operationId()) || !text(value.sessionRevision())) {
            throw deny(INVOCATION_INVALID);
        }
        lease(value.capturedAt(), value.expiresAt(), before, now);
    }
    private TargetSelector selector(Invocation invocation, TargetSelector supplied) {
        TargetSelector selected = supplied;
        if (selected == null && requirements.selection() == Selection.SELF
                && requirements.targetKind() == TargetKind.PERSON) {
            selected = new TargetSelector(TargetKind.PERSON, invocation.personPublicId(), null, null, null);
        }
        if (selected == null || selected.kind() != requirements.targetKind() || !uuid(selected.personPublicId())
                || (requirements.selection() == Selection.SELF
                    && !selected.personPublicId().equals(invocation.personPublicId()))) throw deny(TARGET_INVALID);
        if (selected.kind() == TargetKind.PERSON) {
            if (selected.workerPublicId() != null || selected.workRelationshipPublicId() != null
                    || selected.assignmentPublicId() != null) throw deny(TARGET_INVALID);
        } else if (!uuid(selected.workerPublicId()) || !uuid(selected.workRelationshipPublicId())
                || !uuid(selected.assignmentPublicId())) throw deny(TARGET_INVALID);
        return selected;
    }
    private void dwp(Invocation invocation, DwpAuthoritySnapshot value, Instant before, Instant now) {
        if (value == null || !requirements.equals(value.requirements()) || value.decision() != Decision.ALLOWED
                || value.authRevision() == null || !sha(value.authRevision().value(), "auth-")
                || value.policyRevision() == null || !nativePolicy(value.policyRevision().value())
                || value.contextKey() == null || !sha(value.contextKey().value(), "psc-")
                || value.decisionRevision() == null || !sha(value.decisionRevision().value(), "psr-")
                || !text(value.selectedScopeKey())) throw deny(AUTHORITY_MISMATCH);
        AuthPersonBindingV1 actor = value.actor();
        if (actor == null || actor.tenantId() != invocation.tenantId() || actor.userId() != invocation.userId()
                || !invocation.principalPublicId().equals(actor.principalPublicId())
                || !Objects.equals(invocation.personPublicId(), actor.personPublicId())
                || actor.userRowVersion() != invocation.expectedUserRowVersion()
                || actor.accessRevision() != invocation.expectedAccessRevision()
                || actor.identityPlane() != AuthPersonBindingV1.IdentityPlane.TENANT
                || actor.status() != AuthPersonBindingV1.Status.ACTIVE) throw deny(AUTHORITY_MISMATCH);
        lease(value.capturedAt(), value.expiresAt(), before, now);
        lease(actor.capturedAt(), actor.expiresAt(), before, now);
        if (!value.appEntitled()) throw deny(APP_DENIED);
        if (!value.staticSodAllowed()) throw deny(SOD_DENIED);
        if (!strings(value.grantedPermissions(), true)
                || !value.grantedPermissions().containsAll(requirements.requiredPermissions())) throw deny(PERMISSION_DENIED);
        if (!strings(value.grantedAtomicDuties(), true)
                || !value.grantedAtomicDuties().containsAll(requirements.requiredAtomicDuties())) throw deny(DUTY_DENIED);
    }
    private void people(DwpAuthoritySnapshot dwp, TargetSelector selector, PeopleAuthoritySnapshot value,
            Instant before, Instant now) {
        if (value == null || !requirements.equals(value.requirements())
                || !dwp.selectedScopeKey().equals(value.selectedScopeKey())) throw deny(SCOPE_DENIED);
        lease(value.capturedAt(), value.expiresAt(), before, now);
        revision(value.relationshipRevision(), OwnerRevisionKind.RELATIONSHIP);
        revision(value.populationRevision(), OwnerRevisionKind.POPULATION);
        revision(value.fieldPolicyRevision(), OwnerRevisionKind.FIELD_POLICY);
        revision(value.purposePolicyRevision(), OwnerRevisionKind.PURPOSE_POLICY);
        revision(value.dynamicSodRevision(), OwnerRevisionKind.DYNAMIC_SOD);
        if (!value.populationAllowed()) throw deny(SCOPE_DENIED);
        if (!value.purposeAllowed()) throw deny(PURPOSE_DENIED);
        if (!value.dynamicSodAllowed()) throw deny(SOD_DENIED);
        if (!strings(value.allowedFieldPaths(), true)
                || !value.allowedFieldPaths().containsAll(requirements.fieldPaths())) throw deny(FIELD_DENIED);
        TargetSnapshot target = value.target();
        if (target == null || target.kind() != selector.kind() || target.tenantId() != dwp.actor().tenantId()
                || !selector.personPublicId().equals(target.personPublicId()) || target.personVersion() < 0
                || !text(target.personState())) throw deny(TARGET_INVALID);
        if (target.kind() == TargetKind.PERSON) {
            if (target.workerPublicId() != null || target.workerPersonPublicId() != null || target.workerVersion() != null
                    || target.workRelationshipPublicId() != null || target.relationshipWorkerPublicId() != null
                    || target.workRelationshipVersion() != null || target.assignmentPublicId() != null
                    || target.assignmentRelationshipPublicId() != null || target.assignmentVersion() != null) throw deny(TARGET_INVALID);
        } else if (!selector.workerPublicId().equals(target.workerPublicId())
                || !target.personPublicId().equals(target.workerPersonPublicId()) || !version(target.workerVersion())
                || !selector.workRelationshipPublicId().equals(target.workRelationshipPublicId())
                || !target.workerPublicId().equals(target.relationshipWorkerPublicId()) || !version(target.workRelationshipVersion())
                || !selector.assignmentPublicId().equals(target.assignmentPublicId())
                || !target.workRelationshipPublicId().equals(target.assignmentRelationshipPublicId())
                || !version(target.assignmentVersion())) throw deny(TARGET_INVALID);
    }
    private static boolean sameDwp(DwpAuthoritySnapshot a, DwpAuthoritySnapshot b) {
        return Objects.equals(a.requirements(), b.requirements())
                && sameActor(a.actor(), b.actor()) && a.decision() == b.decision()
                && Objects.equals(a.authRevision(), b.authRevision()) && Objects.equals(a.policyRevision(), b.policyRevision())
                && Objects.equals(a.contextKey(), b.contextKey()) && Objects.equals(a.decisionRevision(), b.decisionRevision())
                && Objects.equals(a.selectedScopeKey(), b.selectedScopeKey())
                && Objects.equals(a.grantedPermissions(), b.grantedPermissions())
                && Objects.equals(a.grantedAtomicDuties(), b.grantedAtomicDuties())
                && a.appEntitled() == b.appEntitled() && a.staticSodAllowed() == b.staticSodAllowed();
    }
    private static boolean sameActor(AuthPersonBindingV1 a, AuthPersonBindingV1 b) {
        return a.tenantId() == b.tenantId() && a.userId() == b.userId()
                && Objects.equals(a.principalPublicId(), b.principalPublicId())
                && Objects.equals(a.personPublicId(), b.personPublicId()) && a.identityPlane() == b.identityPlane()
                && a.status() == b.status() && a.userRowVersion() == b.userRowVersion() && a.accessRevision() == b.accessRevision();
    }
    private static boolean samePeople(PeopleAuthoritySnapshot a, PeopleAuthoritySnapshot b) {
        return Objects.equals(a.requirements(), b.requirements()) && Objects.equals(a.selectedScopeKey(), b.selectedScopeKey())
                && Objects.equals(a.target(), b.target()) && Objects.equals(a.relationshipRevision(), b.relationshipRevision())
                && Objects.equals(a.populationRevision(), b.populationRevision()) && Objects.equals(a.fieldPolicyRevision(), b.fieldPolicyRevision())
                && Objects.equals(a.purposePolicyRevision(), b.purposePolicyRevision())
                && Objects.equals(a.dynamicSodRevision(), b.dynamicSodRevision())
                && Objects.equals(a.allowedFieldPaths(), b.allowedFieldPaths()) && a.populationAllowed() == b.populationAllowed()
                && a.purposeAllowed() == b.purposeAllowed() && a.dynamicSodAllowed() == b.dynamicSodAllowed();
    }
    private static void revision(PeopleRevision value, OwnerRevisionKind kind) {
        if (value == null || value.kind() != kind || !text(value.value())) throw deny(AUTHORITY_MISMATCH);
    }
    private void lease(Instant issued, Instant expiry, Instant before, Instant now) {
        if (issued == null || expiry == null || issued.isBefore(before) || issued.isAfter(now)
                || !expiry.isAfter(now) || !expiry.isAfter(issued)
                || Duration.between(issued, expiry).compareTo(MAX_LEASE) > 0) throw deny(AUTHORITY_STALE);
    }
    private Instant now(Instant previous) {
        Instant value;
        try { value = clock.instant(); } catch (Exception unavailable) { throw deny(CLOCK_INVALID); }
        if (value == null || (previous != null && value.isBefore(previous))) throw deny(CLOCK_INVALID);
        return value;
    }
    private static <T> T owner(Supplier<T> call) {
        try { return call.get(); } catch (CurrentHrisAuthorizationExceptionV2 rejected) { throw deny(rejected.code()); }
        catch (Exception unavailable) { throw deny(OWNER_UNAVAILABLE); }
    }
    private static boolean strings(Set<String> values, boolean emptyAllowed) {
        return values != null && (emptyAllowed || !values.isEmpty()) && values.stream().allMatch(GuardedCurrentHrisAuthorizationPortV2::text);
    }
    private static boolean text(String value) {
        return value != null && !value.isBlank() && value.length() <= 500 && value.equals(value.trim())
                && value.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c) || c == ',');
    }
    private static boolean sha(String value, String prefix) { return value != null && value.matches(prefix + "[a-f0-9]{64}"); }
    private static boolean nativePolicy(String value) { return text(value) && value.startsWith("policy-") && value.length() > 7; }
    private static boolean uuid(UUID value) { return value != null && !value.equals(new UUID(0, 0)); }
    private static boolean version(Long value) { return value != null && value >= 0; }
    private static Instant minimum(Instant... values) {
        Instant result = values[0]; for (Instant value : values) if (value.isBefore(result)) result = value; return result;
    }
    private static CurrentHrisAuthorizationExceptionV2 deny(CurrentHrisAuthorizationExceptionV2.Code code) {
        return new CurrentHrisAuthorizationExceptionV2(code);
    }
}
