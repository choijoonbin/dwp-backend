package com.dwp.services.time.workregime;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import com.dwp.services.time.workregime.WorkRegimeModels.Authority;
import com.dwp.services.time.workregime.WorkRegimeModels.Duty;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.WorkRegimeRevision;

/** Enforces the exact Wave 1 authoring lifecycle and its separation-of-duty boundary. */
public final class WorkRegimeLifecycleGuard {

    public static final String REQUIRED_PURPOSE = "TIME_CONFIGURATION";

    private static final Map<LifecycleAction, Transition> TRANSITIONS = transitions();

    public enum DenialCode {
        TENANT_MISMATCH,
        AUTHORITY_REVOKED,
        PURPOSE_MISMATCH,
        OUT_OF_SCOPE,
        STALE_VERSION,
        DUTY_MISSING,
        STEP_UP_REQUIRED,
        INVALID_TRANSITION,
        SELF_APPROVAL,
        APPROVAL_EVIDENCE_MISSING,
        SEPARATION_OF_DUTY
    }

    /** A stable denial intended to be mapped to a fail-closed command result by the application. */
    public static final class LifecycleDeniedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final DenialCode code;

        LifecycleDeniedException(DenialCode code, String message) {
            super(message);
            this.code = code;
        }

        public DenialCode code() {
            return code;
        }
    }

    /**
     * Authorizes and applies one optimistic transition.
     *
     * <p>The returned value is a new immutable revision view with its row version incremented. The
     * caller still owns durable receipt, aggregate persistence and audit/outbox publication.
     */
    public WorkRegimeRevision transition(
            WorkRegimeRevision current,
            LifecycleAction action,
            Authority authority,
            long expectedVersion) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(authority, "authority must not be null");

        authorizeContext(current, authority, expectedVersion);
        Transition transition = TRANSITIONS.get(action);
        if (transition == null) {
            throw denied(DenialCode.INVALID_TRANSITION,
                    "action is not a work-regime lifecycle transition");
        }
        if (!authority.duties().contains(transition.duty())) {
            throw denied(DenialCode.DUTY_MISSING,
                    "authority does not contain the duty required for this action");
        }
        if (current.state() != transition.from()) {
            throw denied(DenialCode.INVALID_TRANSITION,
                    "action is not valid from the aggregate's current state");
        }
        if (action != LifecycleAction.PUBLISH && current.approvalActorId() != null) {
            throw denied(DenialCode.APPROVAL_EVIDENCE_MISSING,
                    "approval evidence is inconsistent with the aggregate state");
        }
        if (action == LifecycleAction.PUBLISH && !authority.stepUpSatisfied()) {
            throw denied(DenialCode.STEP_UP_REQUIRED,
                    "publishing requires current step-up evidence");
        }

        Long approvalActorId = current.approvalActorId();
        if (action == LifecycleAction.APPLY_APPROVAL) {
            if (authority.actorId() == current.authorActorId()) {
                throw denied(DenialCode.SELF_APPROVAL,
                        "the author cannot approve the same work-regime revision");
            }
            approvalActorId = authority.actorId();
        }
        if (action == LifecycleAction.PUBLISH) {
            requireIndependentPublisher(current, authority);
        }

        long nextVersion;
        try {
            nextVersion = Math.addExact(current.version(), 1L);
        } catch (ArithmeticException overflow) {
            throw denied(DenialCode.STALE_VERSION, "aggregate version cannot advance");
        }
        return new WorkRegimeRevision(
                current.tenantId(),
                current.publicId(),
                current.revision(),
                nextVersion,
                transition.to(),
                current.authorActorId(),
                approvalActorId,
                current.scopeRef(),
                current.period(),
                current.artifactDigest());
    }

    private static void authorizeContext(
            WorkRegimeRevision current, Authority authority, long expectedVersion) {
        if (authority.tenantId() != current.tenantId()) {
            throw denied(DenialCode.TENANT_MISMATCH,
                    "authority and aggregate tenants do not match");
        }
        if (authority.revoked()) {
            throw denied(DenialCode.AUTHORITY_REVOKED, "authority has been revoked");
        }
        if (!REQUIRED_PURPOSE.equals(authority.purpose())) {
            throw denied(DenialCode.PURPOSE_MISMATCH,
                    "authority purpose is not valid for time configuration");
        }
        if (!authority.scopeRefs().contains(current.scopeRef())) {
            throw denied(DenialCode.OUT_OF_SCOPE,
                    "authority does not cover the work-regime scope");
        }
        if (expectedVersion < 1 || current.version() != expectedVersion) {
            throw denied(DenialCode.STALE_VERSION, "aggregate version is stale");
        }
    }

    private static void requireIndependentPublisher(
            WorkRegimeRevision current, Authority authority) {
        Long approver = current.approvalActorId();
        if (approver == null || approver <= 0 || approver == current.authorActorId()) {
            throw denied(DenialCode.APPROVAL_EVIDENCE_MISSING,
                    "an independent approval actor is required before publishing");
        }
        if (authority.actorId() == current.authorActorId()
                || authority.actorId() == approver.longValue()) {
            throw denied(DenialCode.SEPARATION_OF_DUTY,
                    "publisher must be independent from both author and approver");
        }
    }

    private static Map<LifecycleAction, Transition> transitions() {
        Map<LifecycleAction, Transition> transitions = new EnumMap<>(LifecycleAction.class);
        transitions.put(
                LifecycleAction.VALIDATE,
                new Transition(PolicyState.DRAFT, PolicyState.VALIDATED, Duty.TIME_CONFIG_AUTHOR));
        transitions.put(
                LifecycleAction.SIMULATE,
                new Transition(
                        PolicyState.VALIDATED,
                        PolicyState.SIMULATED,
                        Duty.TIME_CONFIG_AUTHOR));
        transitions.put(
                LifecycleAction.SUBMIT_REVIEW,
                new Transition(
                        PolicyState.SIMULATED,
                        PolicyState.IN_REVIEW,
                        Duty.TIME_CONFIG_AUTHOR));
        transitions.put(
                LifecycleAction.APPLY_APPROVAL,
                new Transition(
                        PolicyState.IN_REVIEW,
                        PolicyState.APPROVED,
                        Duty.TIME_CONFIG_APPROVER));
        transitions.put(
                LifecycleAction.PUBLISH,
                new Transition(
                        PolicyState.APPROVED,
                        PolicyState.PUBLISHED,
                        Duty.TIME_CONFIG_APPROVER));
        return Map.copyOf(transitions);
    }

    private static LifecycleDeniedException denied(DenialCode code, String message) {
        return new LifecycleDeniedException(code, message);
    }

    private record Transition(PolicyState from, PolicyState to, Duty duty) {
    }
}
