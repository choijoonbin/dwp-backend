package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalFormReferenceDirectory.MutationPins;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Only genuine request metadata can seal a command; missing keys are never synthesized. */
@Component
public class ApprovalFormReferenceMutationContext {
    private final ObjectProvider<HttpServletRequest> requests;
    public ApprovalFormReferenceMutationContext(ObjectProvider<HttpServletRequest> requests) { this.requests = requests; }
    public MutationPins pins(UUID serverTarget, long dbVersion, String normalizedPayloadSha256) {
        HttpServletRequest request = requests.getIfAvailable();
        if (request == null) throw unavailable();
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw unavailable();
        return new MutationPins(serverTarget, dbVersion, normalizedPayloadSha256, keys.getFirst(), request.getMethod(), request.getRequestURI());
    }
    private BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Exact original mutation metadata is unavailable.");
    }
}
