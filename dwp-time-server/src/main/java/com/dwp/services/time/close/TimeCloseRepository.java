package com.dwp.services.time.close;

import com.dwp.services.time.close.TimeCloseModels.HandoffRecord;
import com.dwp.services.time.close.TimeCloseModels.PersistedClosedResult;
import com.dwp.services.time.close.TimeCloseModels.PlannedCloseCommand;
import com.dwp.services.time.close.TimeCloseModels.StoredReceipt;
import com.dwp.services.time.close.TimeCloseModels.TimeCloseCommandReceiptV1;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped persistence boundary; each command is one database transaction. */
interface TimeCloseRepository {
    Optional<TimeCloseModels.ClosePeriod> findPeriod(long tenantId, UUID periodId);

    Optional<PersistedClosedResult> findResult(long tenantId, UUID resultId);

    Optional<HandoffRecord> findHandoff(long tenantId, UUID handoffId);

    Optional<StoredReceipt> findReceipt(long tenantId, UUID receiptId);

    Optional<StoredReceipt> findReceipt(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String routeContractKey,
            String idempotencyKey);

    List<TimeCloseIaQueryModels.CloseProjectionRow> queryProjectionRows(
            long tenantId,
            UUID legalEntityPublicId,
            UUID timeGroupPublicId,
            String closeScope,
            Instant asOf);

    TimeCloseCommandReceiptV1 execute(PlannedCloseCommand planned);

    TimeCloseModels.PayrollTimeHandoffStatusV1 applyOutcome(
            long tenantId,
            UUID handoffId,
            String outcomeCommandDigest,
            TimeCloseModels.PayrollTimeHandoffOutcomeV1 outcome);
}
