package com.dwp.services.provider;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.support.ProviderSupportAccessService;
import com.dwp.services.provider.support.ProviderSupportActivationService;
import com.dwp.services.provider.support.ProviderSupportDtos;
import com.dwp.services.provider.support.ProviderSupportLedgerService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderControlPlaneControllerSupportContextTest {

    private final ProviderControlPlaneService service = mock(ProviderControlPlaneService.class);
    private final ProviderSupportAccessService accessService = mock(ProviderSupportAccessService.class);
    private final ProviderSupportActivationService activationService =
            mock(ProviderSupportActivationService.class);
    private final ProviderSupportLedgerService ledgerService =
            mock(ProviderSupportLedgerService.class);
    private final ProviderControlPlaneController controller = new ProviderControlPlaneController(
            service, accessService, activationService, ledgerService, true);

    @Test
    void clearsTheBrowserCredentialOnlyForAnExpectedMissingContext() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(accessService.inspect("expired-token", "context-read"))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));

        ApiResponse<ProviderSupportDtos.BrowserSessionContext> result =
                controller.supportSessionContext("expired-token", "context-read", response);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isNull();
        verify(response, times(3)).addHeader(
                eq(HttpHeaders.SET_COOKIE), contains("DWP_SUPPORT_SESSION="));
    }

    @Test
    void propagatesAuditOrAuthorityOutageWithoutClearingTheCredential() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        BaseException unavailable = new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Support lifecycle evidence is temporarily unavailable.");
        when(accessService.inspect("valid-looking-token", "context-read"))
                .thenThrow(unavailable);

        assertThatThrownBy(() -> controller.supportSessionContext(
                "valid-looking-token", "context-read", response))
                .isSameAs(unavailable)
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
                    assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(503);
                });
        verify(response, never()).addHeader(eq(HttpHeaders.SET_COOKIE), contains("DWP_SUPPORT_SESSION="));
    }

    @Test
    void activationReturnsTheExactTransactionalProjectionWithoutListingTheLedger() {
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(300);
        ProviderDtos.SupportSessionSummary session = new ProviderDtos.SupportSessionSummary(
                sessionId, requestId, tenantId, "tenant-a", "Tenant A", 71L, "Operator",
                "ACTIVE", "Diagnosis", List.of("TENANT_EXPERIENCE_PREVIEW"), "STANDARD",
                "approval", true, "L1", now, expiresAt, now, null, 1L);
        ProviderSupportDtos.AccessRequestLedgerItem projection =
                new ProviderSupportDtos.AccessRequestLedgerItem(
                        requestId, tenantId, "tenant-a", "Tenant A", true, "Operator",
                        "ACTIVE", "STANDARD", "Diagnosis",
                        List.of("TENANT_EXPERIENCE_PREVIEW"), 5, "approval", true, "L1",
                        now, expiresAt, sessionId, now, null, "PENDING", 2L);
        when(service.activateSupportAccessRequest(
                eq(requestId), eq("activate"),
                org.mockito.ArgumentMatchers.any(ProviderDtos.ActivateSupportAccessRequest.class)))
                .thenReturn(new ProviderDtos.SupportSessionGrant(session, "token", projection));
        HttpServletResponse response = mock(HttpServletResponse.class);

        ApiResponse<ProviderSupportDtos.AccessRequestLedgerItem> result =
                controller.activateSupportAccessRequest(
                        requestId, "activate",
                        new ProviderDtos.ActivateSupportAccessRequest(1L), response);

        assertThat(result.getData()).isSameAs(projection);
        verify(ledgerService, never()).accessRequests(org.mockito.ArgumentMatchers.any());
        verify(response, times(3)).addHeader(
                eq(HttpHeaders.SET_COOKIE), contains("DWP_SUPPORT_SESSION="));
    }
}
