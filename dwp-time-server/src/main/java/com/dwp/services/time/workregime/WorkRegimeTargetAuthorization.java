package com.dwp.services.time.workregime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.workregime.WorkRegimeModels.Authority;
import com.dwp.services.time.workregime.WorkRegimeModels.CommandReceipt;
import com.dwp.services.time.workregime.WorkRegimeModels.Duty;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeOwnerAuthoritySource.VerifiedRequest;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetAuthorizationGuard;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetBindingEvidence;
import com.dwp.services.time.workregime.WorkRegimeRepository.WorkPlanRecord;
import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.PopulationAccess;
import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.TargetMembershipEvidence;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/** Revalidates owner, population, and target membership evidence at every use-case boundary. */
final class WorkRegimeTargetAuthorization {

    private final WorkRegimeRepository repository;
    private final WorkRegimeTargetPopulationResolver resolver;
    private final Clock clock;

    WorkRegimeTargetAuthorization(
            WorkRegimeRepository repository,
            WorkRegimeTargetPopulationResolver resolver,
            Clock clock) {
        this.repository = repository;
        this.resolver = resolver;
        this.clock = clock;
    }

    OwnedPlan ownedPlan(VerifiedRequest verified, UUID publicId) {
        Authority authority = verified.authority();
        PopulationAccess access = requireCurrentAccess(verified);
        TargetAuthorizationGuard guard = guard(verified, access);
        WorkPlanRecord record = repository.findByPublicId(guard, publicId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireScope(authority, record.revision().scopeRef());
        requireCurrentTargetMembership(access, record);
        return new OwnedPlan(access, guard, record);
    }

    TargetAuthorizationGuard guard(VerifiedRequest verified, PopulationAccess access) {
        return new TargetAuthorizationGuard(
                access.tenantId(), access.actorId(), verified.contextScopeKey(),
                access.populationPublicId(), access.populationRevision(), access.grantRevision(),
                access.populationDigest(), access.grantDigest(), clock.instant());
    }

    PopulationAccess requireCurrentAccess(VerifiedRequest verified) {
        Authority authority = verified.authority();
        PopulationAccess access;
        try {
            access = resolver.resolveActorAccess(
                            authority.tenantId(), authority.actorId(),
                            verified.contextScopeKey(), clock.instant())
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                            "Current TIM target-population authority is unavailable."));
        } catch (BaseException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current TIM target-population authority could not be resolved.", unavailable);
        }
        boolean exact = access.tenantId() == authority.tenantId()
                && access.actorId() == authority.actorId()
                && access.gatewayScopeKey().equals(verified.contextScopeKey())
                && access.validUntil().isAfter(clock.instant())
                && authority.scopeRefs().equals(Set.of(access.scopePublicRef()));
        if (!exact) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current TIM target-population authority is stale or mismatched.");
        }
        return access;
    }

    TargetMembershipEvidence requireCurrentTargetMembership(
            PopulationAccess access, WorkPlanRecord plan) {
        TargetBindingEvidence saved = plan.targetBindingEvidence();
        if (!saved.populationPublicId().equals(access.populationPublicId())
                || !plan.revision().scopeRef().equals(access.scopePublicRef())
                || saved.populationRevision() != access.populationRevision()
                || !saved.populationDigest().equals(access.populationDigest())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        TargetMembershipEvidence current = requireCurrentTargetMembership(
                access, plan.workerPublicId(), plan.peopleAssignmentPublicId(),
                plan.peopleAssignmentRevision(), plan.assignmentPeriod());
        if (saved.populationRevision() != current.populationRevision()
                || !saved.populationDigest().equals(current.populationDigest())
                || saved.membershipRevision() != current.membershipRevision()
                || !saved.membershipDigest().equals(current.membershipDigest())) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Stored TIM target-population binding evidence is stale or mismatched.");
        }
        return current;
    }

    TargetMembershipEvidence requireCurrentTargetMembership(
            PopulationAccess access,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            EffectivePeriod period) {
        TargetMembershipEvidence membership;
        try {
            membership = resolver.resolveTargetMembership(
                            access.tenantId(), access.populationPublicId(), workerPublicId,
                            peopleAssignmentPublicId, peopleAssignmentRevision, period,
                            clock.instant())
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.FORBIDDEN,
                            "The selected People assignment is outside the current target population."));
        } catch (BaseException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current TIM target-population membership could not be resolved.", unavailable);
        }
        if (membership.tenantId() != access.tenantId()
                || !membership.populationPublicId().equals(access.populationPublicId())
                || !membership.workerPublicId().equals(workerPublicId)
                || !membership.peopleAssignmentPublicId().equals(peopleAssignmentPublicId)
                || membership.peopleAssignmentRevision() != peopleAssignmentRevision
                || !membership.effectivePeriod().contains(period)
                || membership.populationRevision() != access.populationRevision()
                || !membership.populationDigest().equals(access.populationDigest())) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "TIM target-population projections are inconsistent.");
        }
        return membership;
    }

    TargetBindingEvidence bindingEvidence(
            Authority authority,
            VerifiedRequest verified,
            PopulationAccess access,
            TargetMembershipEvidence membership) {
        return new TargetBindingEvidence(
                access.populationPublicId(), access.populationRevision(),
                membership.workerPublicId(), membership.peopleAssignmentPublicId(),
                membership.peopleAssignmentRevision(), membership.membershipRevision(),
                authority.actorId(), verified.contextScopeKey(), access.grantRevision(),
                access.populationDigest(), membership.membershipDigest(),
                access.grantDigest(), clock.instant());
    }

    static void requireScope(Authority authority, String scopeRef) {
        if (!covers(authority, scopeRef)) throw new BaseException(ErrorCode.FORBIDDEN);
    }

    static boolean covers(Authority authority, String scopeRef) {
        return authority.scopeRefs().contains(scopeRef);
    }

    static void requireDuty(Authority authority, Duty duty) {
        if (!authority.duties().contains(duty)) throw new BaseException(ErrorCode.FORBIDDEN);
    }

    static void requireOwnerDuty(Authority authority) {
        if (authority.duties().stream().noneMatch(Set.of(
                Duty.TIME_CONFIG_AUTHOR, Duty.TIME_CONFIG_APPROVER,
                Duty.TIME_OPERATOR, Duty.TIME_AUDITOR)::contains)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    static void requireReceiptAccess(Authority authority, CommandReceipt receipt) {
        boolean denied = receipt.operation() == LifecycleAction.REVISE_DRAFT
                || receipt.operation() == LifecycleAction.ASSIGN
                || authority.revoked()
                || authority.actorId() != receipt.actorId()
                || !authority.purpose().equals(receipt.purpose())
                || !authority.scopeRefs().contains(receipt.scopePublicRef())
                || authority.duties().stream().noneMatch(Set.of(
                        Duty.TIME_CONFIG_AUTHOR, Duty.TIME_CONFIG_APPROVER,
                        Duty.TIME_OPERATOR, Duty.TIME_AUDITOR)::contains);
        if (denied) throw new BaseException(ErrorCode.NOT_FOUND);
    }

    record OwnedPlan(
            PopulationAccess access,
            TargetAuthorizationGuard guard,
            WorkPlanRecord plan) { }
}
