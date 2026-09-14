package com.dwp.services.approval.documentretention.management;

import java.util.UUID;

/** Fresh Auth owner proof, not a cached end-user token or an inferred local role. */
public interface ApprovalRetentionExecutionAuthorityPort {
    record SignedAuthorization(String payloadBase64Url,String signatureBase64Url) {}
    record Target(UUID intentId,long tenantId,long actorId,UUID requestId,String resourceSetKey,
            long requestVersion,UUID policyId,long policyVersion,long holdVersion,String inventorySha256,
            String commandFingerprint,long intentVersion) {}
    SignedAuthorization current(Target target);
}
