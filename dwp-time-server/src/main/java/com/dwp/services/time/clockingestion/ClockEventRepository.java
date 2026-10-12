package com.dwp.services.time.clockingestion;

import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAppend;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockCommandReceipt;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEvent;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockExceptionCandidate;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockExceptionProjectionQuery;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionCandidate;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionPage;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionQuery;
import java.util.Optional;
import java.util.UUID;

/** Atomic persistence boundary for clock facts, durable receipts, and outbox events. */
public interface ClockEventRepository {

    ClockCommandReceipt append(ClockAppend append);

    Optional<ClockEvent> find(long tenantId, UUID clockEventId);

    ClockProjectionPage<ClockProjectionCandidate> queryClock(ClockProjectionQuery query);

    ClockProjectionPage<ClockExceptionCandidate> queryExceptions(
            ClockExceptionProjectionQuery query);
}
