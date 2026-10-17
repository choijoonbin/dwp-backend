package com.dwp.services.payroll.preparation;

import com.dwp.services.payroll.security.PayrollStepUpEvidence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface PayrollPreparationStore {

    void bindTenant(long tenantId);

    void lockPreparation(long tenantId, UUID preparationId);

    Optional<PayrollPreparationModels.StoredCommand> command(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String idempotencyKey);

    void consumeStepUp(
            long tenantId,
            UUID preparationId,
            UUID subjectPrincipalPublicId,
            String idempotencyKey,
            String requestDigest,
            PayrollStepUpEvidence evidence,
            String useKind,
            Instant usedAt);

    PayrollPreparationModels.EntryQueryResponse population(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.EntryQuery query);

    PayrollPreparationModels.PreparationCandidate candidate(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.PrepareCommand command);

    void commit(PayrollPreparationModels.PreparationCommit commit);
}
