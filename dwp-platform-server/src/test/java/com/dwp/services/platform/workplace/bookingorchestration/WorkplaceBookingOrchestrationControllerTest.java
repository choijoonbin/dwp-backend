package com.dwp.services.platform.workplace.bookingorchestration;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationController.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}
