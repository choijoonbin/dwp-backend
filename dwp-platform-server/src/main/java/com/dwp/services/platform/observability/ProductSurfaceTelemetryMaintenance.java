package com.dwp.services.platform.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

@Component
public class ProductSurfaceTelemetryMaintenance {

    static final int RAW_RETENTION_DAYS = 30;
    static final int DAILY_RETENTION_DAYS = 180;
    private static final Logger log =
            LoggerFactory.getLogger(ProductSurfaceTelemetryMaintenance.class);

    private final ProductSurfaceTelemetryRepository repository;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;

    @Autowired
    public ProductSurfaceTelemetryMaintenance(
            ProductSurfaceTelemetryRepository repository,
            @Value("${dwp.platform.product-surface-telemetry.maintenance-enabled:true}")
                    boolean enabled,
            @Value("${dwp.platform.product-surface-telemetry.maintenance-batch-size:1000}")
                    int batchSize) {
        this(repository, Clock.systemUTC(), enabled, batchSize);
    }

    ProductSurfaceTelemetryMaintenance(
            ProductSurfaceTelemetryRepository repository,
            Clock clock,
            boolean enabled,
            int batchSize) {
        if (batchSize < 1 || batchSize > 5000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 5000");
        }
        this.repository = repository;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${dwp.platform.product-surface-telemetry.maintenance-cron:0 41 2 * * *}")
    public void rollUpAndPurge() {
        if (!enabled) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        OffsetDateTime rollupFrom = today.minusDays(RAW_RETENTION_DAYS)
                .atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime rollupTo = today.atStartOfDay().atOffset(ZoneOffset.UTC);
        int dailyRows = repository.rollUp(rollupFrom, rollupTo);
        long rawRows = purgeRaw(now.minusDays(RAW_RETENTION_DAYS));
        long aggregateRows = purgeDaily(today.minusDays(DAILY_RETENTION_DAYS));
        if (dailyRows + rawRows + aggregateRows > 0) {
            log.info(
                    "Product surface UX telemetry refreshed {} daily rows, purged {} raw "
                            + "rows and {} expired aggregate rows",
                    dailyRows, rawRows, aggregateRows);
        }
    }

    private long purgeRaw(OffsetDateTime cutoff) {
        return drainExpired(
                "raw",
                repository.countRawBefore(cutoff),
                () -> repository.purgeRaw(cutoff, batchSize),
                () -> repository.countRawBefore(cutoff));
    }

    private long purgeDaily(LocalDate cutoff) {
        return drainExpired(
                "aggregate",
                repository.countDailyBefore(cutoff),
                () -> repository.purgeDaily(cutoff, batchSize),
                () -> repository.countDailyBefore(cutoff));
    }

    private long drainExpired(
            String store,
            long initialBacklog,
            IntSupplier purgeBatch,
            LongSupplier remainingBacklog) {
        if (initialBacklog < 0) {
            throw retentionFailure(store, "reported a negative initial backlog");
        }
        long total = 0;
        while (total < initialBacklog) {
            int removed = purgeBatch.getAsInt();
            long snapshotRemaining = initialBacklog - total;
            if (removed < 0 || removed > batchSize || removed > snapshotRemaining) {
                throw retentionFailure(store,
                        "returned an invalid purge batch size of " + removed);
            }
            if (removed == 0) {
                break;
            }
            try {
                total = Math.addExact(total, removed);
            } catch (ArithmeticException exception) {
                throw retentionFailure(store, "overflowed its purge progress counter");
            }
        }
        long remaining = remainingBacklog.getAsLong();
        if (remaining < 0) {
            throw retentionFailure(store, "reported a negative remaining backlog");
        }
        if (remaining > 0) {
            throw retentionFailure(store,
                    "did not drain: initial=" + initialBacklog
                            + ", removed=" + total + ", remaining=" + remaining
                            + "; rows may be locked or expired-row ingestion may be occurring");
        }
        return total;
    }

    private IllegalStateException retentionFailure(String store, String detail) {
        String message = "Product surface UX telemetry " + store
                + " retention failed: " + detail;
        log.error(message);
        return new IllegalStateException(message);
    }
}
