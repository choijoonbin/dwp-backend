package com.dwp.services.people.hr.performance.compensationplanning;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tenant-scoped rate and concurrency admission for the PAY snapshot refetch plane. */
@Component
final class ApprovedCompensationSnapshotAdmissionControl {

    private static final String PREFIX =
            "dwp.hris.performance.compensation.snapshot-service.";

    private final Clock clock;
    private final int maxConcurrentPerTenant;
    private final int requestsPerMinute;
    private final ConcurrentHashMap<Long, TenantWindow> tenants = new ConcurrentHashMap<>();

    @Autowired
    ApprovedCompensationSnapshotAdmissionControl(Environment environment) {
        this(
                Clock.systemUTC(),
                bounded(environment, "max-concurrent-per-tenant", 2, 1, 32),
                bounded(environment, "requests-per-minute", 60, 1, 10_000));
    }

    ApprovedCompensationSnapshotAdmissionControl(
            Clock clock,
            int maxConcurrentPerTenant,
            int requestsPerMinute) {
        this.clock = clock;
        this.maxConcurrentPerTenant = maxConcurrentPerTenant;
        this.requestsPerMinute = requestsPerMinute;
    }

    Decision admit(long tenantId) {
        TenantWindow window = tenants.computeIfAbsent(
                tenantId, ignored -> new TenantWindow(maxConcurrentPerTenant));
        long epochSecond = clock.instant().getEpochSecond();
        long minute = Math.floorDiv(epochSecond, 60);
        synchronized (window) {
            if (window.minute != minute) {
                window.minute = minute;
                window.requests = 0;
            }
            if (window.requests >= requestsPerMinute) {
                int retryAfter = Math.toIntExact(
                        Math.max(1, 60 - Math.floorMod(epochSecond, 60)));
                return Decision.denied("SVC_PEP_PER_001_RATE_LIMITED", retryAfter);
            }
            if (!window.concurrent.tryAcquire()) {
                return Decision.denied("SVC_PEP_PER_001_CONCURRENCY_LIMITED", 1);
            }
            window.requests++;
        }
        return Decision.allowed(new Lease(window.concurrent));
    }

    private static int bounded(
            Environment environment,
            String name,
            int defaultValue,
            int minimum,
            int maximum) {
        Integer value = environment.getProperty(PREFIX + name, Integer.class, defaultValue);
        if (value == null || value < minimum || value > maximum) {
            throw new IllegalStateException(
                    PREFIX + name + " must be between " + minimum + " and " + maximum + ".");
        }
        return value;
    }

    record Decision(Lease lease, String reason, int retryAfterSeconds) {
        static Decision allowed(Lease lease) {
            return new Decision(lease, null, 0);
        }

        static Decision denied(String reason, int retryAfterSeconds) {
            return new Decision(null, reason, retryAfterSeconds);
        }

        boolean allowed() {
            return lease != null;
        }
    }

    static final class Lease implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }

    private static final class TenantWindow {
        private final Semaphore concurrent;
        private long minute = Long.MIN_VALUE;
        private int requests;

        private TenantWindow(int maxConcurrent) {
            this.concurrent = new Semaphore(maxConcurrent, true);
        }
    }
}
