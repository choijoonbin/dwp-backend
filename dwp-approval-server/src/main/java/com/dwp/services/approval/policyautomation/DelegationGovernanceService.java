package com.dwp.services.approval.policyautomation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.*;

@Service
public class DelegationGovernanceService {
    private final PolicyAutomationRepository commandReceipts;
    private final DelegationGovernanceRepository repository;
    private final Clock clock;
    private final ApprovalIdentityDirectory identities;

    @Autowired
    public DelegationGovernanceService(
            PolicyAutomationRepository commandReceipts,
            DelegationGovernanceRepository repository,
            Clock clock,
            ApprovalIdentityDirectory identities) {
        this.commandReceipts = commandReceipts;
        this.repository = repository;
        this.clock = clock;
        this.identities = identities;
    }

    DelegationGovernanceService(PolicyAutomationRepository commandReceipts,
            DelegationGovernanceRepository repository, Clock clock) {
        this(commandReceipts, repository, clock, null);
    }

    @Transactional(readOnly = true)
    public List<DelegationView> delegations() {
        return repository.delegations(readContext(), clock.instant());
    }

    @Transactional(readOnly = true)
    public DelegationView delegation(UUID delegationId) {
        return repository.requireDelegation(
                readContext(), delegationId, false, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<DelegationReviewView> reviews(UUID delegationId) {
        return repository.reviews(readContext(), delegationId, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<DelegationAuditEvent> audit(UUID delegationId, int limit) {
        return repository.audit(readContext(), delegationId, limit, clock.instant());
    }

    @Transactional
    public DelegationView create(String idempotencyKey, DelegationDraft input) {
        validate(input, null);
        Context context = Context.current(idempotencyKey);
        DelegationIdentity delegator = identity(context, input.delegatorUserId());
        DelegationIdentity delegate = identity(context, input.delegateUserId());
        validateRoles(input, delegator);
        return idempotent(context, "CREATE_DELEGATION", input.delegationId(), input,
                DelegationView.class, () -> repository.create(context, input, delegator, delegate, clock.instant()));
    }

    @Transactional
    public DelegationView update(String idempotencyKey, UUID delegationId, DelegationDraft input) {
        if (input == null || delegationId == null || !delegationId.equals(input.delegationId())) {
            throw PolicyAutomationRejected.invalid("Delegation path and payload targets must match.");
        }
        Context context = Context.current(idempotencyKey);
        DelegationView current = repository.requireDelegation(context, delegationId, false, clock.instant());
        validate(input, current.startsAt());
        DelegationIdentity delegator = identity(context, input.delegatorUserId());
        DelegationIdentity delegate = identity(context, input.delegateUserId());
        validateRoles(input, delegator);
        return idempotent(context, "UPDATE_DELEGATION", delegationId, input,
                DelegationView.class, () -> repository.update(context, input, delegator, delegate, clock.instant()));
    }

    @Transactional
    public DelegationView revoke(String idempotencyKey, UUID delegationId,
            DelegationStateCommand input, boolean scheduledOnly) {
        validate(input);
        Context context = Context.current(idempotencyKey);
        String operation = scheduledOnly ? "CANCEL_SCHEDULED_DELEGATION" : "REVOKE_DELEGATION";
        return idempotent(context, operation, delegationId, input, DelegationView.class,
                () -> repository.revoke(context, delegationId, input, scheduledOnly, clock.instant()));
    }

    @Transactional
    public DelegationKillSwitchView killSwitch(String idempotencyKey,
            DelegationKillSwitchCommand input) {
        if (input == null || input.killSwitchId() == null || input.expectedControlVersion() == null
                || input.expectedControlVersion() < 0 || !reason(input.reason())) {
            throw PolicyAutomationRejected.invalid("Delegation kill-switch command is invalid.");
        }
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "KILL_SWITCH_DELEGATIONS", input.killSwitchId(), input,
                DelegationKillSwitchView.class, () -> repository.killSwitch(context, input, clock.instant()));
    }

    @Transactional
    public DelegationReviewView review(
            String idempotencyKey,
            UUID delegationId,
            DelegationReviewCommand input) {
        Context context = Context.current(idempotencyKey);
        DelegationReviewView prior = commandReceipts.prior(context, "REVIEW_DELEGATION",
                delegationId, input, DelegationReviewView.class);
        if (prior != null) return prior;
        DelegationReviewView result = repository.review(
                context, delegationId, input, clock.instant());
        commandReceipts.complete(context, "REVIEW_DELEGATION", input, result);
        return result;
    }

    private void validate(DelegationDraft input, java.time.Instant retainedStart) {
        java.time.Instant now = clock.instant();
        boolean retained = retainedStart != null && retainedStart.equals(input == null ? null : input.startsAt());
        if (input == null || input.delegationId() == null || input.delegatorUserId() == null
                || input.delegateUserId() == null || input.expectedVersion() == null
                || input.expectedVersion() < 0 || input.delegatorUserId() < 1 || input.delegateUserId() < 1
                || input.delegatorUserId().equals(input.delegateUserId()) || input.startsAt() == null
                || input.endsAt() == null || !input.endsAt().isAfter(input.startsAt())
                || !input.endsAt().isAfter(now)
                || input.startsAt().isBefore(now.minus(Duration.ofMinutes(5))) && !retained
                || Duration.between(input.startsAt(), input.endsAt()).compareTo(Duration.ofDays(90)) > 0
                || !Set.of("ALL", "WORKFLOW").contains(input.scopeType()) || !reason(input.reason())
                || input.delegatedRoleCodes().isEmpty() || input.delegatedRoleCodes().size() > 50
                || input.delegatedRoleCodes().stream().distinct().count() != input.delegatedRoleCodes().size()
                || input.delegatedRoleCodes().stream().anyMatch(role -> role == null
                        || !role.matches("[A-Z][A-Z0-9_]{1,79}") || role.startsWith("PROVIDER_"))) {
            throw PolicyAutomationRejected.invalid("Delegation administration command is invalid.");
        }
    }

    private void validate(DelegationStateCommand input) {
        if (input == null || input.expectedVersion() == null || input.expectedVersion() < 0
                || !reason(input.reason())) {
            throw PolicyAutomationRejected.invalid("Delegation state command is invalid.");
        }
    }

    private void validateRoles(DelegationDraft input, DelegationIdentity delegator) {
        if (!delegator.roleCodes().containsAll(input.delegatedRoleCodes())) {
            throw PolicyAutomationRejected.forbidden("Delegated roles exceed the delegator's current authority.");
        }
    }

    private DelegationIdentity identity(Context context, long userId) {
        if (identities == null) {
            throw PolicyAutomationRejected.unavailable("Canonical identity directory is unavailable.");
        }
        final ApprovalIdentityDirectory.Subject subject;
        try {
            subject = identities.require(context.tenantId(), userId);
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.NOT_FOUND || exception.getErrorCode() == ErrorCode.FORBIDDEN) {
                throw PolicyAutomationRejected.forbidden("Delegation subject is not active in this tenant.");
            }
            throw PolicyAutomationRejected.unavailable("Delegation identity could not be revalidated.");
        } catch (RuntimeException exception) {
            throw PolicyAutomationRejected.unavailable("Delegation identity could not be revalidated.");
        }
        if (subject == null || !subject.active() || !Long.valueOf(context.tenantId()).equals(subject.tenantId())
                || !Long.valueOf(userId).equals(subject.userId()) || subject.personPublicId() == null
                || subject.displayName() == null || subject.displayName().isBlank()
                || subject.email() == null || subject.email().isBlank() || subject.roles() == null
                || subject.roles().stream().anyMatch(role -> role == null || role.startsWith("PROVIDER_"))) {
            throw PolicyAutomationRejected.forbidden("Delegation subject is not active in this tenant.");
        }
        return new DelegationIdentity(userId, subject.personPublicId(), subject.displayName(),
                subject.email(), subject.roles());
    }

    private boolean reason(String value) {
        return value != null && value.length() >= 10 && value.length() <= 1000
                && value.equals(value.strip()) && value.codePoints().noneMatch(Character::isISOControl);
    }

    private <T> T idempotent(Context context, String operation, UUID target, Object input,
            Class<T> type, Supplier<T> command) {
        T prior = commandReceipts.prior(context, operation, target, input, type);
        if (prior != null) return prior;
        T result = command.get();
        commandReceipts.complete(context, operation, input, result);
        return result;
    }

    private Context readContext() {
        return Context.current("read-" + UUID.randomUUID());
    }
}
