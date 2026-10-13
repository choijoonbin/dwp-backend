package com.dwp.services.time.interpretation;

import com.dwp.services.time.interpretation.InterpretationModels.InterpretCommand;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAppend;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationCommandReceipt;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationInputs;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationQuery;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationRun;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped atomic boundary for frozen inputs, immutable attempts, ledger, receipt, and outbox. */
public interface InterpretationRepository {

    Optional<InterpretationCommandReceipt> findReceipt(
            long tenantId, UUID subjectPrincipalPublicId, UUID idempotencyKey);

    InterpretationInputs loadInputs(long tenantId, InterpretCommand command);

    Optional<InterpretationRun> latestSucceeded(
            long tenantId, List<UUID> workerIds, InterpretCommand command);

    InterpretationCommandReceipt append(InterpretationAppend append);

    Optional<InterpretationRun> findRun(long tenantId, UUID runPublicId);

    List<InterpretationSummary> query(InterpretationQuery query);
}
