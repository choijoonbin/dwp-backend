package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.ConnectorExecutionRequestV1;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.Operation;

/** Single closed operation-to-PDX projection used at acceptance and dispatch. */
final class IntegrationExecutionArtifacts {

    private IntegrationExecutionArtifacts() {}

    static ConnectorExecutionRequestV1 project(
            Sys008TypedArtifactContract.OwnerIssuedExecutionContext context,
            Sys008TypedArtifactContract.CurrentPopulationContext population,
            Sys008TypedArtifactContract.VerifiedSourceEnvelope source,
            Operation operation,
            String mappingDirection) {
        ConnectorExecutionRequestV1 projected = switch (operation) {
            case IMPORT -> {
                requireDirection(mappingDirection, "INBOUND");
                yield Sys008TypedArtifactContract.projectImportExecution(
                        context, source);
            }
            case EXPORT -> {
                requireDirection(mappingDirection, "OUTBOUND");
                yield Sys008TypedArtifactContract.projectExportExecution(
                        context, source);
            }
            case DISPATCH -> {
                requireDirection(mappingDirection, "OUTBOUND");
                yield Sys008TypedArtifactContract.projectDispatchExecution(
                        context, source);
            }
            case RECONCILE -> {
                ConnectorExecutionRequestV1 frozenOriginal;
                if ("INBOUND".equals(mappingDirection)) {
                    frozenOriginal = Sys008TypedArtifactContract
                            .projectImportExecution(context, source);
                } else {
                    requireDirection(mappingDirection, "OUTBOUND");
                    frozenOriginal = Sys008TypedArtifactContract
                            .projectDispatchExecution(context, source);
                }
                yield Sys008TypedArtifactContract.projectReconcile(
                        context, frozenOriginal, population);
            }
        };
        return Sys008TypedArtifactContract.requireInteroperable(projected);
    }

    private static void requireDirection(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "SYS008_EXECUTION_DIRECTION_MISMATCH");
        }
    }
}
