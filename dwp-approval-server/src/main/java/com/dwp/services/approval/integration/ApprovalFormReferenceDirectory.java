package com.dwp.services.approval.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mutation references have a distinct proof profile; candidate DATA proofs are never reused. */
public interface ApprovalFormReferenceDirectory {
    String PURPOSE = "APPROVAL_FORM_REFERENCE_RESOLVE_V1";
    String ISSUER = "dwp-approval-server:form-reference-resolve:v1";
    String AUDIENCE = "dwp-auth-server:approval-form-reference-resolve:v1";
    Map<String, String> ROUTES = Map.of(
            "route.approvals.work.request-create.action", "CREATE",
            "route.approvals.work.request-draft-update.action", "UPDATE",
            "route.approvals.work.request-submit.action", "SUBMIT",
            "route.approvals.work.request-information-response.action", "INFORMATION",
            "route.approvals.work.request-draft-recover.action", "RECOVER");

    ApprovalFormUserDirectory.Result resolve(ApprovalFormUserDirectory.Authority authority, MutationPins pins, List<UUID> people);

    record MutationPins(UUID targetRequestId, long targetRequestVersion, String mutationPayloadSha256,
            String idempotencyKey, String mutationMethod, String mutationPath) {
        public MutationPins {
            if (targetRequestId == null || targetRequestVersion < 0 || targetRequestVersion > 9007199254740991L || mutationPayloadSha256 == null
                    || !mutationPayloadSha256.matches("[a-f0-9]{64}") || idempotencyKey == null
                    || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,120}") || mutationMethod == null || mutationPath == null) throw invalid();
        }

        public void requireRoute(String route) {
            String operation = ROUTES.get(route);
            if (operation == null) throw invalid();
            String prefix = "/v1/requests/" + targetRequestId;
            String expectedPath = switch (operation) {
                case "CREATE" -> "/v1/requests";
                case "UPDATE" -> prefix + "/draft";
                case "SUBMIT" -> prefix + "/submit";
                case "INFORMATION" -> prefix + "/information-response";
                default -> prefix + "/draft/recover";
            };
            if (!(operation.equals("UPDATE") ? "PUT" : "POST").equals(mutationMethod)
                    || !expectedPath.equals(mutationPath) || operation.equals("CREATE") && targetRequestVersion != 0) throw invalid();
        }

        private static BaseException invalid() {
            return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Exact owner-sealed approval mutation pins are required.");
        }
    }
}
