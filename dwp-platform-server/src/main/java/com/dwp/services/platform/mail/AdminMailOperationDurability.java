package com.dwp.services.platform.mail;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Commits TEST_SEND admission and completion independently of the provider call. */
@Component
class AdminMailOperationDurability {

    private final AdminMailCompletionRepository repository;
    private final TransactionTemplate requiresNew;

    AdminMailOperationDurability(
            AdminMailCompletionRepository repository,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    Claim claimTestSend(
            long tenantId,
            long actorId,
            UUID connectionId,
            String scope,
            Map<String, Object> payload,
            UUID idempotencyKey,
            String fingerprint,
            String correlationId) {
        return required(requiresNew.execute(status -> {
            UUID operationId = repository.insertDurableTestSendOperation(
                    tenantId, actorId, connectionId, scope, payload,
                    idempotencyKey, fingerprint, correlationId).orElse(null);
            if (operationId != null) {
                return new Claim(operationId, true, null);
            }
            AdminMailCompletionRepository.ConnectionOperationRow winner = repository
                    .connectionOperation(tenantId, actorId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Durable TEST_SEND claim winner is unavailable"));
            return new Claim(winner.id(), false, winner);
        }));
    }

    AdminMailCompletionRepository.ConnectionOperationRow complete(
            long tenantId,
            long actorId,
            UUID idempotencyKey,
            UUID operationId,
            String state,
            String errorCode,
            OffsetDateTime evidenceAt) {
        return required(requiresNew.execute(status -> {
            repository.completeConnectionOperation(
                    tenantId, operationId, state, errorCode, evidenceAt);
            return repository.connectionOperation(tenantId, actorId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Durable TEST_SEND completion evidence is unavailable"));
        }));
    }

    private <T> T required(T value) {
        if (value == null) {
            throw new IllegalStateException("Durable mail transaction returned no result");
        }
        return value;
    }

    record Claim(
            UUID operationId,
            boolean created,
            AdminMailCompletionRepository.ConnectionOperationRow existing) { }
}
