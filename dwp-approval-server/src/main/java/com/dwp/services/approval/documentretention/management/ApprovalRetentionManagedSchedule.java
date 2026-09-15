package com.dwp.services.approval.documentretention.management;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded scheduler facade; database leases provide the cross-pod execution fence. */
public final class ApprovalRetentionManagedSchedule {
    private static final Logger log=LoggerFactory.getLogger(ApprovalRetentionManagedSchedule.class);
    private final ApprovalRetentionManagedWorker worker;
    private final int batchSize;
    private final AtomicBoolean running=new AtomicBoolean();

    public ApprovalRetentionManagedSchedule(ApprovalRetentionManagedWorker worker,int batchSize) {
        this.worker=java.util.Objects.requireNonNull(worker);
        if(batchSize<1 || batchSize>100) throw new IllegalArgumentException("Retention worker batch size must be 1 to 100");
        this.batchSize=batchSize;
        log.info("Managed retention worker configured dependencies={}",worker.configuredDependencies());
    }

    @Scheduled(fixedDelayString="${dwp.approval.retention-worker.poll-delay-ms:2000}",
            initialDelayString="${dwp.approval.retention-worker.initial-delay-ms:10000}")
    public void poll() {runCycle();}

    public int runCycle() {
        if(!running.compareAndSet(false,true)) return 0;
        int processed=0;
        try {
            while(processed<batchSize && worker.runOne()) processed++;
        } catch(RuntimeException failure) {
            log.error("Managed retention cycle stopped after a durable fail-closed outcome",failure);
        } finally {running.set(false);}
        return processed;
    }
}
