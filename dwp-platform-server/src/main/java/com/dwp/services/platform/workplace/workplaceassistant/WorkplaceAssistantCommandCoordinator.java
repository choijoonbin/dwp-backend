package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.CommandRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantAuditRepository.ActiveConfirmation;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class WorkplaceAssistantCommandCoordinator {
    private final WorkplaceAssistantAuditRepository repository;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    @Autowired
    public WorkplaceAssistantCommandCoordinator(
            WorkplaceAssistantAuditRepository repository,
            PlatformTransactionManager transactionManager) {
        this(repository, transactionManager, Clock.systemUTC());
    }

    WorkplaceAssistantCommandCoordinator(
            WorkplaceAssistantAuditRepository repository,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public CommandClaim claim(CommandRow accepted) {
        return Objects.requireNonNull(requiresNew.execute(status -> claimInTransaction(accepted)));
    }

    private CommandClaim claimInTransaction(CommandRow accepted) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!repository.lockLiveAssistantRequest(
                accepted.tenantId(), accepted.actorUserId(), accepted.requestId(), now)) {
            throw WorkplaceAssistantSupport.conflict(
                    "The Assistant request content retention period has expired.");
        }
        repository.lockCommand(accepted.tenantId(), accepted.actorUserId(),
                accepted.commandType(), accepted.idempotencyKey());
        CommandRow existing = repository.command(
                accepted.tenantId(), accepted.actorUserId(), accepted.commandType(),
                accepted.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(accepted.requestFingerprint())) {
                throw WorkplaceAssistantSupport.conflict(
                        "The idempotency key was already used for different input.");
            }
            return new CommandClaim(existing, false, claimExecution(existing));
        }
        repository.lockConfirmationRequest(accepted.tenantId(), accepted.requestId());
        ActiveConfirmation activeConfirmation = repository.activeConfirmation(
                accepted.tenantId(), accepted.requestId()).orElse(null);
        CommandRow active = activeConfirmation == null ? null : activeConfirmation.command();
        if (active != null
                && active.requestFingerprint().equals(accepted.requestFingerprint())
                && activeConfirmation.leaseUntil() != null
                && !activeConfirmation.leaseUntil().isAfter(now)
                && repository.expireConfirmationClaim(
                        accepted.tenantId(), active.commandId(), now)) {
            repository.createCommand(accepted);
            return new CommandClaim(accepted, true, claimExecution(accepted));
        }
        if (active != null) {
            CommandRow rejected = new CommandRow(
                    accepted.commandId(), accepted.tenantId(), accepted.actorUserId(),
                    accepted.requestId(), accepted.commandType(), accepted.idempotencyKey(),
                    accepted.requestFingerprint(), WorkplaceAssistantDtos.CommandState.FAILED,
                    accepted.statusHref(), accepted.reason(), accepted.correlationId(),
                    "CONFIRMATION_ALREADY_IN_PROGRESS", accepted.acceptedAt(), now);
            repository.createCommand(rejected);
            return new CommandClaim(rejected, true, null);
        }
        repository.createCommand(accepted);
        return new CommandClaim(accepted, true, claimExecution(accepted));
    }

    private UUID claimExecution(CommandRow command) {
        if (!"CONFIRM_REQUEST".equals(command.commandType())
                || command.state() != WorkplaceAssistantDtos.CommandState.ACCEPTED) {
            return null;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID token = UUID.randomUUID();
        return repository.claimExecution(command.tenantId(), command.commandId(),
                token, now, now.plusSeconds(30)) ? token : null;
    }

    public record CommandClaim(
            CommandRow command, boolean created, UUID executionClaimToken) {
        public boolean executionClaimed() {
            return executionClaimToken != null;
        }
    }
}
