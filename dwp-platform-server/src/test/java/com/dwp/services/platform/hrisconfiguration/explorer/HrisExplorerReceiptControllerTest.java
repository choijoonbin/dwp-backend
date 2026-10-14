package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrisExplorerReceiptControllerTest {

    private static final UUID RECEIPT_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000831");

    @Test
    void explorerProfileUsesCurrentExplorerSubjectAndOriginatingContext() {
        HrisExplorerService service = mock(HrisExplorerService.class);
        HrisExplorerReceipt expected = receipt(
                JdbcHrisExplorerRepository.RECENT_ACTION,
                "HRIS_APP_DISCOVERY");
        when(service.explorerReceipt(snapshot(), RECEIPT_ID)).thenReturn(expected);
        HrisExplorerController controller = new HrisExplorerController(service);
        MockHttpServletRequest request = request(
                "explorer-originating-receipt-reader");
        request.setAttribute(
                HrisExplorerOwnerEvidenceAdapter.SNAPSHOT_ATTRIBUTE, snapshot());

        var response = controller.receipt(request, RECEIPT_ID);

        assertThat(response.getBody()).isEqualTo(expected);
        verify(service).explorerReceipt(snapshot(), RECEIPT_ID);
    }

    @Test
    void genericPlatformProfileUsesOnlyTypedCurrentReceiptAuthority() {
        HrisExplorerService service = mock(HrisExplorerService.class);
        HrisExplorerReceipt expected = receipt(
                "HRIS_CONFIGURATION_PUBLISH", "HRIS_CONFIGURATION_CHANGE");
        when(service.platformReceipt(42, SUBJECT, RECEIPT_ID)).thenReturn(expected);
        HrisExplorerController controller = new HrisExplorerController(service);
        MockHttpServletRequest request = request(
                "platform-originating-command-receipt-reader");
        request.setAttribute(
                HrisPlatformReceiptAuthorityAdapter.VERIFIED_ATTRIBUTE,
                new HrisPlatformReceiptAuthorityAdapter.VerifiedReceiptAuthority(
                        42, SUBJECT, "HRIS_CONFIGURATION_PUBLISH", POPULATION,
                        7, "HRIS_CONFIGURATION_CHANGE", 11,
                        CONTEXT, SCOPE, DECISION,
                        "psc-" + "7".repeat(64),
                        "scope-" + "8".repeat(32),
                        "psr-" + "9".repeat(64)));

        var response = controller.receipt(request, RECEIPT_ID);

        assertThat(response.getBody()).isEqualTo(expected);
        verify(service).platformReceipt(42, SUBJECT, RECEIPT_ID);
    }

    @Test
    void genericProfileWithoutTypedOriginatingAuthorityIsOpaqueNotFound() {
        HrisExplorerController controller = new HrisExplorerController(
                mock(HrisExplorerService.class));
        MockHttpServletRequest request = request(
                "platform-originating-command-receipt-reader");

        assertThatThrownBy(() -> controller.receipt(request, RECEIPT_ID))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));
    }

    private MockHttpServletRequest request(String profileKey) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/admin/hris/command-receipts/" + RECEIPT_ID);
        request.setAttribute(
                HcmPlatformPepRegistry.class.getName() + ".authority",
                new HcmPlatformPepRegistry.RouteAuthority(
                        "route.hcm.utility.explorer-command-receipt.data",
                        "DATA", "GET",
                        "/v1/admin/hris/command-receipts/{receiptId}",
                        profileKey, false, "hcm.command-receipt.view",
                        "NORMAL", null));
        return request;
    }

    private HrisExplorerReceipt receipt(String action, String purpose) {
        return new HrisExplorerReceipt(
                RECEIPT_ID, action, action, SUBJECT, POPULATION,
                7, purpose, 11, ReceiptStatus.SUCCEEDED,
                UUID.fromString("00000000-0000-4000-8000-000000000832"),
                "b".repeat(64), NOW, 2L, "result:sealed");
    }
}
