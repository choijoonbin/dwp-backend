package com.dwp.services.platform.workplace;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Bounds a maintenance invocation between database batches. */
final class WorkplaceMaintenanceRunBudget {

    static final int MAX_BATCH_SIZE = 5000;
    static final int MAX_BATCHES_PER_RUN = 1000;
    static final long MIN_RUN_MILLIS = 100;
    static final long MAX_RUN_MILLIS = Duration.ofMinutes(5).toMillis();

    private final Clock clock;
    private final Instant deadline;
    private final int maxBatches;
    private int startedBatches;

    WorkplaceMaintenanceRunBudget(Clock clock, int maxBatches, long maxRunMillis) {
        this.clock = clock;
        validateMaxBatches(maxBatches);
        validateMaxRunMillis(maxRunMillis);
        this.maxBatches = maxBatches;
        this.deadline = clock.instant().plusMillis(maxRunMillis);
    }

    boolean tryStartBatch() {
        if (startedBatches >= maxBatches || !clock.instant().isBefore(deadline)) {
            return false;
        }
        startedBatches++;
        return true;
    }

    int startedBatches() {
        return startedBatches;
    }

    static void validateBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and 5000");
        }
    }

    static void validateMaxBatches(int maxBatches) {
        if (maxBatches < 1 || maxBatches > MAX_BATCHES_PER_RUN) {
            throw new IllegalArgumentException("maxBatches must be between 1 and 1000");
        }
    }

    static void validateMaxRunMillis(long maxRunMillis) {
        if (maxRunMillis < MIN_RUN_MILLIS || maxRunMillis > MAX_RUN_MILLIS) {
            throw new IllegalArgumentException("maxRunMillis must be between 100 and 300000");
        }
    }
}
