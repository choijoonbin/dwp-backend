package com.dwp.services.auth.controller;

import com.dwp.core.exception.BaseException;
import com.dwp.core.security.Hrm012OwnerReceiptAuthorityContract;
import com.dwp.services.auth.config.ProductSurfaceInternalSecurityConfig;
import com.dwp.services.auth.dto.Hrm012ReceiptFirstStepUpDtos;
import com.dwp.services.auth.service.Hrm012ReceiptFirstStepUpVerificationService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Hrm012ReceiptFirstStepUpVerificationControllerTest {

    @Test
    void permitsOnlyTheAuthenticatedPeopleOwner() {
        Hrm012ReceiptFirstStepUpVerificationService service =
                mock(Hrm012ReceiptFirstStepUpVerificationService.class);
        var controller = new Hrm012ReceiptFirstStepUpVerificationController(service);
        var request = request();
        var response = new Hrm012ReceiptFirstStepUpDtos.VerifyResponse(
                "ALLOWED", "psr-1", "auth-1", "feature-1", "challenge-1",
                OffsetDateTime.parse("2026-10-22T02:00:00Z"));
        when(service.verify(request)).thenReturn(response);

        assertThatThrownBy(() -> controller.verify("dwp-gateway", request))
                .isInstanceOf(BaseException.class);
        assertThat(controller.verify(
                ProductSurfaceInternalSecurityConfig.PEOPLE_SERVICE_IDENTITY, request))
                .isSameAs(response);
        verify(service).verify(request);
    }

    private Hrm012ReceiptFirstStepUpDtos.VerifyRequest request() {
        return new Hrm012ReceiptFirstStepUpDtos.VerifyRequest(
                7L, "11", Hrm012OwnerReceiptAuthorityContract.ROUTE, "POST",
                Hrm012OwnerReceiptAuthorityContract.PUBLIC_PATH,
                Hrm012OwnerReceiptAuthorityContract.SERVICE_PATH,
                "connector-1", "\"3\"", "HRIS_INTEGRATION", "challenge",
                "idempotency-1", "context-1", "scope-1", "a".repeat(64),
                "psr-1", "receipt-1", "b".repeat(64), "correlation-1");
    }
}
