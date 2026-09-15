package com.dwp.services.approval.documentretention.management;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;

/** One-intent managed dispatch; no scheduler or app-role deletion permission is granted. */
public final class ApprovalRetentionIntentExecutor {
    private static final Duration LEASE=Duration.ofSeconds(90);
    private final ApprovalRetentionManagedExecutionRepository executions;
    private final ApprovalRetentionExecutionAuthorityPort authority;
    private final ApprovalRetentionExecutionVerifier verifier;
    public ApprovalRetentionIntentExecutor(DataSource executor,ApprovalRetentionExecutionAuthorityPort authority,
            ApprovalRetentionExecutionVerifier verifier) {
        this.executions=new ApprovalRetentionManagedExecutionRepository(executor);
        this.authority=authority;this.verifier=verifier;
    }
    public UUID dispatch(UUID id,long version) {
        if(id==null || version<0) throw new IllegalArgumentException("Exact retention intent version required");
        executions.expireLostLeases();
        var lease=executions.claim(id,version,"retention-intent-dispatch",LEASE);
        if(lease==null) throw ApprovalRetentionErrors.conflict();
        if(!"AUTHORITY".equals(lease.stage())) {
            executions.block(lease,"DISPATCH_STAGE_CHANGED");
            throw ApprovalRetentionErrors.conflict();
        }
        if(!authority.configured() || !verifier.configured()) {
            String reason=!authority.configured()?"AUTHORITY_PORT_NOT_CONFIGURED":"AUTHORITY_VERIFIER_NOT_CONFIGURED";
            executions.block(lease,reason);
            throw ApprovalRetentionErrors.dependencyNotConfigured(reason);
        }
        try {
            String proof=verifier.verify(lease.target(),authority.current(lease.target()));
            return executions.dispatch(lease,proof);
        } catch(BaseException failure) {
            try {
                if(failure instanceof ApprovalRetentionErrors.DependencyNotConfigured missing)
                    executions.block(lease,missing.reasonCode());
                else if(failure.getErrorCode()==ErrorCode.FORBIDDEN || failure.getErrorCode()==ErrorCode.SOD_CONFLICT
                        || failure.getErrorCode()==ErrorCode.RESOURCE_CONFLICT || failure.getErrorCode()==ErrorCode.DECISION_REVISION_CONFLICT)
                    executions.block(lease,"CURRENT_AUTHORITY_OR_SOURCE_CHANGED");
                else executions.unknown(lease,"AUTHORITY_OR_DEPENDENCY_UNAVAILABLE");
            } catch(RuntimeException outcomeFailure) {failure.addSuppressed(outcomeFailure);}
            throw failure;
        } catch(RuntimeException unknown) {
            try {
                if(isPermanentFenceFailure(unknown)) executions.block(lease,"CURRENT_SOURCE_FENCE_CHANGED");
                else executions.unknown(lease,"DISPATCH_RESULT_UNKNOWN");
            }
            catch(RuntimeException outcomeFailure) {unknown.addSuppressed(outcomeFailure);}
            throw unknown;
        }
    }

    private static boolean isPermanentFenceFailure(Throwable failure) {
        for(Throwable current=failure;current!=null;current=current.getCause()) {
            if(current instanceof java.sql.SQLException sql
                    && java.util.Set.of("23514","40001").contains(sql.getSQLState())) return true;
        }
        return false;
    }
}
