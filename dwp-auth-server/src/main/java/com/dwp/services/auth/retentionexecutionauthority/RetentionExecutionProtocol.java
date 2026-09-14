package com.dwp.services.auth.retentionexecutionauthority;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.util.Set;

public final class RetentionExecutionProtocol {
    public static final String PATH="/internal/auth/v1/approval-retention-execution-authority/evaluate";
    public static final String HEADER="X-DWP-Approval-Retention-Execution-Token";
    public static final String PREFIX="dwp.auth.approval-retention-execution.";
    public static final String OWNER_ISSUER="dwp-approval-server:retention-execution-source:v1";
    public static final String OWNER_AUDIENCE="dwp-auth-server:retention-execution-source:v1";
    public static final String OWNER_PURPOSE="APPROVAL_RETENTION_EXECUTION_SOURCE_V1";
    public static final String TRANSPORT_ISSUER="dwp-approval-server:retention-execution-transport:v1";
    public static final String TRANSPORT_AUDIENCE="dwp-auth-server:retention-execution-transport:v1";
    public static final String TRANSPORT_PURPOSE="APPROVAL_RETENTION_EXECUTION_TRANSPORT_V1";
    public static final String EXECUTION_PURPOSE="APPROVAL_RETENTION_EXECUTE_V1";
    public static final String ROUTE="route.approvals.admin.retention-record-claim.action";
    public static final String CAPABILITY="approvals.operations.execute";
    public static final String PERMISSION="ADMIN.APPROVAL_OPERATIONS:EXECUTE";
    public static final int OWNER_LIMIT=16384,TRANSPORT_LIMIT=2048,BODY_LIMIT=24576;
    public static final Set<String> STANDARD=Set.of("iss","aud","sub","iat","nbf","exp","jti","purpose");
    private RetentionExecutionProtocol() { }
    public static BaseException denied() {return new BaseException(ErrorCode.FORBIDDEN);}
    public static BaseException changed() {return new BaseException(ErrorCode.DECISION_REVISION_CONFLICT);}
    public static BaseException unavailable() {return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);}
    public record SignedAuthorization(String payloadBase64Url,String signatureBase64Url) { }
}
