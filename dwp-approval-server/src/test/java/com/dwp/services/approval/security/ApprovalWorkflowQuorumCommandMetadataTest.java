package com.dwp.services.approval.security;

import static com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandProof.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** Admission contract only: the verifier is a test double, never evidence of a signed production issuer. */
class ApprovalWorkflowQuorumCommandMetadataTest {
    final UUID target = UUID.randomUUID();
    final UUID person = UUID.randomUUID();
    ContentCachingRequestWrapper request;
    ApprovalWorkflowQuorumCommandProof proof;
    DefaultListableBeanFactory beans;
    ApprovalWorkflowQuorumCommandMetadata metadata;

    @BeforeEach void initialize() throws Exception {
        ApprovalRequestContext.set(100L, 42L, person, Set.of("FINANCE_REVIEWER"), Set.of("ACTION.APPROVAL_TASK:UPDATE"));
        currentContext("revision-1", "110");
        var raw = new MockHttpServletRequest("POST", "/v1/tasks/" + target + "/decisions");
        raw.setContent("{ \"decision\": \"REQUEST_INFO\" }\n".getBytes(StandardCharsets.UTF_8));
        raw.addHeader("Idempotency-Key", "original-1"); raw.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        request = new ContentCachingRequestWrapper(raw, ApprovalWorkflowQuorumBodyFilter.MAX_BODY);
        request.getInputStream().readAllBytes();
        proof = mock(ApprovalWorkflowQuorumCommandProof.class);
        when(proof.verify(any(), any(), any(), anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> verified());
        beans = new DefaultListableBeanFactory(); beans.registerSingleton("request", request); beans.registerSingleton("proof", proof);
        metadata = new ApprovalWorkflowQuorumCommandMetadata(beans.getBeanProvider(jakarta.servlet.http.HttpServletRequest.class),
                beans.getBeanProvider(ApprovalWorkflowQuorumCommandProof.class));
    }
    @AfterEach void clear() { ApprovalRequestContext.clear(); ApprovalDecisionRevisionContext.clear(); }
    void currentContext(String revision, String rollout) {
        ApprovalDecisionRevisionContext.set(revision, OffsetDateTime.now().plusSeconds(25), "request-context", "request-scope",
                "route.approvals.work.task-decision.action", rollout);
    }
    Verified verified() {
        Instant now = Instant.now();
        return new Verified(42, 100, person, Purpose.TASK_INFORMATION, target, "POST", request.getRequestURI(), "original-1",
                hash(request.getContentAsByteArray()), "authority-1", now, now.plusSeconds(20), ApprovalDecisionRevisionContext.current().orElseThrow(), "NORMAL");
    }
    String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    void error(ErrorCode expected, Runnable operation) {
        assertEquals(expected, assertThrows(BaseException.class, operation::run).getErrorCode());
    }

    @Test void exactOriginalConverterBytesArePassedToDedicatedVerifierAndRetainedForCurrentCheck() {
        metadata.prepare(Purpose.TASK_INFORMATION, target);
        var body = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(proof).verify(eq(ApprovalRequestContext.require()), eq(Purpose.TASK_INFORMATION), eq(target), eq("POST"),
                eq(request.getRequestURI()), eq("original-1"), body.capture());
        assertArrayEquals(request.getContentAsByteArray(), body.getValue());
        assertEquals(hash(body.getValue()), metadata.current(ApprovalRequestContext.require(), Purpose.TASK_INFORMATION, target).rawBodySha256());
        assertNotEquals(hash("{\"decision\":\"REQUEST_INFO\"}".getBytes(StandardCharsets.UTF_8)), hash(body.getValue()));
        metadata.clear(); error(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> metadata.current(ApprovalRequestContext.require(), Purpose.TASK_INFORMATION, target));
    }

    @Test void missingProductionVerifierFailsClosedBeforeAnyAuthorityFallback() {
        beans.destroySingleton("proof");
        error(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> metadata.prepare(Purpose.TASK_INFORMATION, target)); verifyNoInteractions(proof);
    }

    @Test void duplicateCommandIdentityFailsBeforeDedicatedVerifier() {
        ((MockHttpServletRequest) request.getRequest()).addHeader("Idempotency-Key", "original-1");
        error(ErrorCode.FORBIDDEN, () -> metadata.prepare(Purpose.TASK_INFORMATION, target)); verifyNoInteractions(proof);
    }

    @Test void queryOrNonCanonicalTargetCannotBorrowExactSignedBody() {
        ((MockHttpServletRequest) request.getRequest()).setQueryString("scope=another");
        error(ErrorCode.FORBIDDEN, () -> metadata.prepare(Purpose.TASK_INFORMATION, target)); verifyNoInteractions(proof);
    }

    @Test void bodyHashAndOpaquePrincipalSubstitutionAreDenied() {
        var original = verified();
        when(proof.verify(any(), any(), any(), anyString(), anyString(), anyString(), any())).thenReturn(new Verified(42, 100, UUID.randomUUID(),
                original.purpose(), target, original.method(), original.path(), original.idempotencyKey(), original.rawBodySha256(), original.revision(),
                original.evaluatedAt(), original.expiresAt(), original.context(), original.accessMode()));
        error(ErrorCode.FORBIDDEN, () -> metadata.prepare(Purpose.TASK_INFORMATION, target));
        when(proof.verify(any(), any(), any(), anyString(), anyString(), anyString(), any())).thenReturn(new Verified(42, 100, person,
                original.purpose(), target, original.method(), original.path(), original.idempotencyKey(), "0".repeat(64), original.revision(),
                original.evaluatedAt(), original.expiresAt(), original.context(), original.accessMode()));
        error(ErrorCode.FORBIDDEN, () -> metadata.prepare(Purpose.TASK_INFORMATION, target));
    }

    @Test void tenantPurposeAndTargetAreNotInterchangeable() {
        var original = verified();
        for (var substituted : java.util.List.of(
                new Verified(43, 100, person, original.purpose(), target, original.method(), original.path(), original.idempotencyKey(), original.rawBodySha256(),
                        original.revision(), original.evaluatedAt(), original.expiresAt(), original.context(), original.accessMode()),
                new Verified(42, 100, person, Purpose.REQUEST_REPLY, target, original.method(), original.path(), original.idempotencyKey(), original.rawBodySha256(),
                        original.revision(), original.evaluatedAt(), original.expiresAt(), original.context(), original.accessMode()),
                new Verified(42, 100, person, original.purpose(), UUID.randomUUID(), original.method(), original.path(), original.idempotencyKey(), original.rawBodySha256(),
                        original.revision(), original.evaluatedAt(), original.expiresAt(), original.context(), original.accessMode()))) {
            when(proof.verify(any(), any(), any(), anyString(), anyString(), anyString(), any())).thenReturn(substituted);
            error(ErrorCode.FORBIDDEN, () -> metadata.prepare(Purpose.TASK_INFORMATION, target));
        }
    }

    @Test void expiredOrOverThirtySecondEvidenceCannotReachDatabasePath() {
        var original = verified();
        for (Instant expiry : java.util.List.of(Instant.now().minusSeconds(1), original.evaluatedAt().plusSeconds(31))) {
            when(proof.verify(any(), any(), any(), anyString(), anyString(), anyString(), any())).thenReturn(new Verified(42, 100, person,
                    original.purpose(), target, original.method(), original.path(), original.idempotencyKey(), original.rawBodySha256(), original.revision(),
                    original.evaluatedAt(), expiry, original.context(), original.accessMode()));
            error(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> metadata.prepare(Purpose.TASK_INFORMATION, target));
        }
    }

    @Test void gatewayRevisionDriftAndChangedKeyAreRecheckedAfterAdmission() {
        metadata.prepare(Purpose.TASK_INFORMATION, target); currentContext("revision-2", "110");
        error(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> metadata.current(ApprovalRequestContext.require(), Purpose.TASK_INFORMATION, target));
        metadata.prepare(Purpose.TASK_INFORMATION, target);
        ((MockHttpServletRequest) request.getRequest()).removeHeader("Idempotency-Key");
        ((MockHttpServletRequest) request.getRequest()).addHeader("Idempotency-Key", "changed-key");
        error(ErrorCode.FORBIDDEN, () -> metadata.current(ApprovalRequestContext.require(), Purpose.TASK_INFORMATION, target));
    }

    @Test void duplicateModeAndProviderIdentityCannotBorrowTenantCommandAdmission() {
        ((MockHttpServletRequest) request.getRequest()).addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        error(ErrorCode.FORBIDDEN, () -> metadata.prepare(Purpose.TASK_INFORMATION, target));
        ((MockHttpServletRequest) request.getRequest()).removeHeader("X-DWP-Active-Access-Mode");
        ((MockHttpServletRequest) request.getRequest()).addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        ApprovalRequestContext.set(100L, 42L, person, Set.of("PROVIDER_REVIEWER"), Set.of());
        error(ErrorCode.FORBIDDEN, () -> metadata.prepare(Purpose.TASK_INFORMATION, target));
    }

    @Test void currentReplyContextCannotAuthorizeTaskDecisionEvenWhenTheVerifierEchoesIt() {
        ApprovalDecisionRevisionContext.set("revision-1", OffsetDateTime.now().plusSeconds(25), "request-context", "request-scope",
                "route.approvals.work.request-information-response.action", "110");
        error(ErrorCode.FORBIDDEN, () -> metadata.prepare(Purpose.TASK_INFORMATION, target));
    }

    @Test void truncatedCacheIsNeverAcceptedWhileLegacyConverterStillReceivesCompleteBody() throws Exception {
        var raw = new MockHttpServletRequest("POST", request.getRequestURI());
        var bytes = new byte[ApprovalWorkflowQuorumBodyFilter.MAX_BODY + 1]; java.util.Arrays.fill(bytes, (byte) 'a'); raw.setContent(bytes);
        raw.addHeader("Idempotency-Key", "original-1"); raw.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        var captured = new AtomicReference<ContentCachingRequestWrapper>();
        new ApprovalWorkflowQuorumBodyFilter().doFilter(raw, new MockHttpServletResponse(), (wrapped, response) -> {
            assertArrayEquals(bytes, wrapped.getInputStream().readAllBytes()); captured.set((ContentCachingRequestWrapper) wrapped);
        });
        request = captured.get(); assertEquals(ApprovalWorkflowQuorumBodyFilter.MAX_BODY, request.getContentAsByteArray().length);
        beans.destroySingleton("request"); beans.registerSingleton("request", request);
        error(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> metadata.prepare(Purpose.TASK_INFORMATION, target)); verifyNoInteractions(proof);
    }
}
