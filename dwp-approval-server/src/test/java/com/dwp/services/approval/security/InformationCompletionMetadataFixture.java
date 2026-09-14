package com.dwp.services.approval.security;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;

import com.dwp.services.approval.workflowauthority.WorkflowRuntimeAttestationVerifier;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** Fixture context only, not installed PEP or canonical-9 authority evidence. */
public final class InformationCompletionMetadataFixture {
    public static ContentCachingRequestWrapper cache(WorkflowRuntimeAttestationVerifier.Verified proof,byte[] bytes) throws java.io.IOException {
        var signed=proof.bindings().get("command");
        ApprovalDecisionRevisionContext.set(text(signed,"decisionRevision",68),OffsetDateTime.ofInstant(Instant.ofEpochSecond(integer(signed,"authorityValidUntil",1)),ZoneOffset.UTC),
                text(signed,"contextKey",500),text(signed,"contextScopeKey",500),text(signed,"routeContractKey",100),text(signed,"rolloutState",3));
        var request=new MockHttpServletRequest("POST",text(signed,"path",250));request.setContent(bytes);
        request.addHeader("Idempotency-Key",text(signed,"idempotencyKey",120));request.addHeader("X-DWP-Active-Access-Mode",text(signed,"accessMode",10));
        var cached=new ContentCachingRequestWrapper(request,ApprovalWorkflowQuorumBodyFilter.MAX_BODY);
        cached.getInputStream().readAllBytes();return cached;
    }
    public static void clear() {ApprovalDecisionRevisionContext.clear();ApprovalRequestContext.clear();}
    private InformationCompletionMetadataFixture() { }
}
