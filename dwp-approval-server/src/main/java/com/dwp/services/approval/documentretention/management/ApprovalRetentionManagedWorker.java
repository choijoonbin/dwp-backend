package com.dwp.services.approval.documentretention.management;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.documentretention.ApprovalRetentionObjectWorker;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static com.dwp.services.approval.documentretention.management.ApprovalRetentionManagedExecutionRepository.*;

/**
 * Opt-in one-step worker. UNKNOWN outcomes require explicit versioned recovery and are never
 * converted into a fresh provider command.
 */
public final class ApprovalRetentionManagedWorker {
    private static final String AUDIT="AUDIT", NOTIFICATION="NOTIFICATION";
    private final ApprovalRetentionManagedExecutionRepository executions;
    private final ApprovalRetentionExecutionAuthorityPort authority;
    private final ApprovalRetentionExecutionVerifier verifier;
    private final ApprovalRetentionObjectWorker objects;
    private final ApprovalRetentionForeignJournal foreign;
    private final Map<String,ApprovalRetentionForeignPort> ports;
    private final String workerId;
    private final Duration leaseDuration;

    public ApprovalRetentionManagedWorker(ApprovalRetentionManagedExecutionRepository executions,
            ApprovalRetentionExecutionAuthorityPort authority,ApprovalRetentionExecutionVerifier verifier,
            ApprovalRetentionObjectWorker objects,ApprovalRetentionForeignJournal foreign,
            Collection<ApprovalRetentionForeignPort> ports,String workerId,Duration leaseDuration) {
        this.executions=java.util.Objects.requireNonNull(executions);
        this.authority=java.util.Objects.requireNonNull(authority);
        this.verifier=java.util.Objects.requireNonNull(verifier);
        this.objects=java.util.Objects.requireNonNull(objects);
        this.foreign=java.util.Objects.requireNonNull(foreign);
        this.workerId=requireWorkerId(workerId);
        this.leaseDuration=requireLease(leaseDuration);
        this.ports=exactPorts(ports);
    }

    public boolean runOne() {
        executions.expireLostLeases();
        Lease lease=executions.claim(null,null,workerId,leaseDuration);
        if(lease==null) return false;
        String dependency=missingDependency(lease.stage());
        if(dependency!=null) {
            executions.block(lease,dependency);
            return true;
        }
        try {
            switch(lease.stage()) {
                case "AUTHORITY" -> authorizeAndDispatch(lease);
                case "OBJECTS" -> processObjects(lease);
                case "FOREIGN" -> processForeign(lease);
                case "LOCAL_DB" -> executions.purgeLocal(lease);
                case "FINALIZE" -> executions.finalizeExecution(lease);
                default -> throw new IllegalStateException("Unknown managed retention stage");
            }
        } catch(BaseException failure) {
            try {durableFailure(lease,failure);}
            catch(RuntimeException outcomeFailure) {failure.addSuppressed(outcomeFailure);throw failure;}
        } catch(RuntimeException failure) {
            try {
                if(isPermanentFenceFailure(failure)) executions.block(lease,"CURRENT_SOURCE_FENCE_CHANGED");
                else executions.unknown(lease,"UNEXPECTED_STAGE_RESULT_UNKNOWN");
            } catch(RuntimeException outcomeFailure) {failure.addSuppressed(outcomeFailure);}
            throw failure;
        }
        return true;
    }

    public long recover(UUID intentId,long expectedExecutionVersion) {
        if(intentId==null || expectedExecutionVersion<0) throw new IllegalArgumentException("Exact unknown execution version required");
        return executions.recover(intentId,expectedExecutionVersion);
    }

    private void authorizeAndDispatch(Lease lease) {
        String proof=verifier.verify(lease.target(),authority.current(lease.target()));
        executions.dispatch(lease,proof);
    }

    private void processObjects(Lease lease) {
        if(lease.executionClaimId()==null) throw new IllegalStateException("Execution claim is unavailable");
        objects.runOne(lease.executionClaimId());
        executions.checkpointObjects(lease);
    }

    private void processForeign(Lease lease) {
        ForeignWork work=executions.claimForeign(lease);
        if(work==null) {executions.checkpointForeign(lease);return;}
        var request=foreign.request(lease.target().intentId(),work.deletionRequestId(),work.consumerService());
        var port=ports.get(work.consumerService());
        ApprovalRetentionForeignDtos.SignedAck signed;
        try {
            signed="DISPATCH".equals(work.recoveryMode())
                    ? port.deleteDeclaredCopies(request) : port.reconcileDeclaredCopies(request);
        } catch(RuntimeException unknown) {
            executions.unknownForeign(lease,work.deletionRequestId(),"OWNER_RESULT_UNKNOWN");
            return;
        }
        if(signed==null) {executions.unknownForeign(lease,work.deletionRequestId(),"OWNER_RESULT_UNKNOWN");return;}
        foreign.acknowledge(work.consumerService(),signed);
        executions.finishForeign(lease,work.deletionRequestId());
    }

    private void durableFailure(Lease lease,BaseException failure) {
        if(failure instanceof ApprovalRetentionErrors.DependencyNotConfigured missing) {
            executions.block(lease,missing.reasonCode());
            return;
        }
        ErrorCode code=failure.getErrorCode();
        if(code==ErrorCode.FORBIDDEN || code==ErrorCode.SOD_CONFLICT || code==ErrorCode.RESOURCE_CONFLICT
                || code==ErrorCode.DECISION_REVISION_CONFLICT) {
            executions.block(lease,"CURRENT_AUTHORITY_OR_SOURCE_CHANGED");
        } else {
            executions.unknown(lease,"AUTHORITY_OR_DEPENDENCY_UNAVAILABLE");
        }
    }

    public Map<String,Boolean> configuredDependencies() {
        var result=new java.util.LinkedHashMap<String,Boolean>();
        result.put("authority",authority.configured() && verifier.configured());
        result.put("objectStorage",objects.configured());
        result.put("auditOwner",ports.get(AUDIT).configured());
        result.put("notificationOwner",ports.get(NOTIFICATION).configured());
        return Map.copyOf(result);
    }

    private String missingDependency(String stage) {
        if("AUTHORITY".equals(stage)) {
            if(!authority.configured()) return "AUTHORITY_PORT_NOT_CONFIGURED";
            if(!verifier.configured()) return "AUTHORITY_VERIFIER_NOT_CONFIGURED";
        }
        if(java.util.Set.of("AUTHORITY","OBJECTS").contains(stage) && !objects.configured())
            return "OBJECT_STORAGE_NOT_CONFIGURED";
        if(java.util.Set.of("AUTHORITY","OBJECTS","FOREIGN").contains(stage)) {
            if(!ports.get(AUDIT).configured()) return "AUDIT_OWNER_NOT_CONFIGURED";
            if(!ports.get(NOTIFICATION).configured()) return "NOTIFICATION_OWNER_NOT_CONFIGURED";
        }
        return null;
    }

    private static boolean isPermanentFenceFailure(Throwable failure) {
        for(Throwable current=failure;current!=null;current=current.getCause()) {
            if(current instanceof java.sql.SQLException sql
                    && java.util.Set.of("23514","40001").contains(sql.getSQLState())) return true;
        }
        return false;
    }

    private static Map<String,ApprovalRetentionForeignPort> exactPorts(Collection<ApprovalRetentionForeignPort> values) {
        if(values==null) throw new IllegalArgumentException("Exact retention owner ports required");
        var result=new HashMap<String,ApprovalRetentionForeignPort>();
        for(var port:values) {
            if(port==null || !java.util.Set.of(AUDIT,NOTIFICATION).contains(port.consumerService())
                    || result.put(port.consumerService(),port)!=null)
                throw new IllegalArgumentException("Duplicate or unknown retention owner port");
        }
        if(!result.keySet().equals(java.util.Set.of(AUDIT,NOTIFICATION)))
            throw new IllegalArgumentException("Both retention owner ports are required");
        return Map.copyOf(result);
    }

    private static String requireWorkerId(String value) {
        if(value==null || !value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$"))
            throw new IllegalArgumentException("Closed retention worker id required");
        return value;
    }

    private static Duration requireLease(Duration value) {
        if(value==null || value.toSeconds()<30 || value.toSeconds()>300 || value.getNano()!=0)
            throw new IllegalArgumentException("Retention lease must be 30 to 300 whole seconds");
        return value;
    }
}
