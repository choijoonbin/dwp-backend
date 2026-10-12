package com.dwp.services.time.clockingestion;

import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAppend;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockCommandReceipt;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEvent;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Atomic persistence boundary for clock facts, durable receipts, and outbox events. */
public interface ClockEventRepository {

    ClockCommandReceipt append(ClockAppend append);

    Optional<ClockEvent> find(long tenantId, UUID clockEventId);

    List<ClockEvent> findRecent(long tenantId, Set<UUID> workerScope, int limit);
}
