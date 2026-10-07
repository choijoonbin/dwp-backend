package com.dwp.services.auth.controller;

import com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.ActorFact;
import com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.AuthPolicyFact;
import com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.AuthScopeFact;
import com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.Code;
import com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.Operation;
import com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.SessionFact;
import com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.SubjectFact;
import com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.TenantFact;
import io.swagger.v3.oas.annotations.media.Schema;

/** Owner-qualified documentation types for the shared Workforce governance ABI. */
public final class WorkforcePolicyGovernanceOpenApi {

    private WorkforcePolicyGovernanceOpenApi() {
    }

    @Schema(
            name = "WorkforcePolicyGovernanceResponse",
            oneOf = {Success.class, Failure.class})
    public sealed interface Response permits Success, Failure {
    }

    @Schema(name = "WorkforcePolicyGovernanceSuccess")
    public record Success(
            int schemaVersion,
            String kind,
            String boundary,
            String result,
            String evidenceId,
            String requestNonce,
            Operation operationId,
            String requestDigest,
            String candidateDigest,
            String capturedAt,
            String issuedAt,
            String expiresAt,
            ActorFact actor,
            TenantFact tenant,
            SessionFact session,
            AuthScopeFact scope,
            AuthPolicyFact authPolicy,
            SubjectFact subject,
            String peopleOwnerValidation,
            String ownerService,
            String audience,
            String purpose) implements Response {
    }

    @Schema(name = "WorkforcePolicyGovernanceFailure")
    public record Failure(
            int schemaVersion,
            String kind,
            String boundary,
            String result,
            String requestNonce,
            Operation operationId,
            Code code) implements Response {
    }
}
