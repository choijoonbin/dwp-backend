package com.dwp.services.platform.workplace.bookingorchestration;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationController.*;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkplaceBookingOrchestrationControllerTest {

    @Test
    void trustedBase64UrlDisplayNameReachesBeneficiaryAuthority() {
        WorkplaceBookingOrchestrationService service =
                mock(WorkplaceBookingOrchestrationService.class);
        WorkplaceBookingOrchestrationController controller =
                new WorkplaceBookingOrchestrationController(service);
        UUID personId = UUID.randomUUID();
        String trustedName = "신뢰 사용자";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(trustedName.getBytes(StandardCharsets.UTF_8));

        controller.beneficiaries(
                42, 99, personId, encoded, "group:trusted", new MockHttpServletResponse());

        verify(service).beneficiaries(42, 99, personId, trustedName, "group:trusted");
    }

    @Test
    void displayNameDecoderIsBoundedStrictUtf8AndRejectsControlCharacters() {
        assertThat(decodeDisplayName(null)).isNull();
        assertThatThrownBy(() -> decodeDisplayName("%%%"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> decodeDisplayName(
                Base64.getUrlEncoder().withoutPadding().encodeToString(" \n "
                        .getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> decodeDisplayName("A".repeat(513)))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> decodeDisplayName("_w"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void holdReleaseForwardsCasCommandAndPreventsResponseCaching() {
        WorkplaceBookingOrchestrationService service =
                mock(WorkplaceBookingOrchestrationService.class);
        WorkplaceBookingOrchestrationController controller =
                new WorkplaceBookingOrchestrationController(service);
        UUID intentId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        HoldReleaseRequest request = new HoldReleaseRequest(
                2L, List.of(new HoldReleaseReference(holdId, 1L)),
                "Edit the plan", true);
        HoldReleaseResult result = new HoldReleaseResult(
                new HoldResponse(intentId, IntentState.PREVIEWED, 3L,
                        OffsetDateTime.parse("2026-09-17T00:00:00Z"), List.of()),
                new HoldReleaseReceipt(UUID.randomUUID(), intentId,
                        HoldReleaseCommandState.SUCCEEDED, List.of(holdId), 3L,
                        false, false, "corr-release",
                        OffsetDateTime.parse("2026-09-17T00:00:00Z")));
        when(service.releaseHolds(
                42L, 99L, "release-key", "corr-release", intentId, request))
                .thenReturn(result);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(controller.releaseHolds(
                42L, 99L, "release-key", "corr-release", intentId, request, response)
                .getData()).isEqualTo(result);

        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("private, no-store, max-age=0");
        verify(service).releaseHolds(
                42L, 99L, "release-key", "corr-release", intentId, request);
    }
}
