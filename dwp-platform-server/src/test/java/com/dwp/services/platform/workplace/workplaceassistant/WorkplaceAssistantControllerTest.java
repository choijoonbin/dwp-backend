package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantController.*;
import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.ValidateAssistantRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkplaceAssistantControllerTest {
    private final WorkplaceAssistantService service = mock(WorkplaceAssistantService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new WorkplaceAssistantController(service)).build();

    @Test
    void trustedBase64UrlDisplayNameReachesAuthoritativeValidationAndPlaintextSpoofIsIgnored()
            throws Exception {
        UUID personId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String trustedName = "신뢰 사용자";
        String encodedName = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(trustedName.getBytes(StandardCharsets.UTF_8));

        mvc.perform(post("/v1/workplace/assistant/requests/{requestId}:validate", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42)
                        .header(USER, 99)
                        .header(PERSON, personId)
                        .header(DISPLAY, encodedName)
                        .header("X-DWP-User-Display-Name", "spoofed plaintext")
                        .header(GROUPS, "group:trusted")
                        .header(PERMISSIONS, UPDATE)
                        .header(LOCALE, "ko-KR")
                        .header(IDEMPOTENCY, "validate-23")
                        .header(CORRELATION, "corr-23")
                        .content("""
                                {
                                  "expectedVersion": 1,
                                  "selectionMode": "ALL",
                                  "selectedProposalItemIds": [],
                                  "requestedHoldTtlSeconds": 120,
                                  "allowAlternatives": true,
                                  "reason": "authoritative validation"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"));

        verify(service).validate(eq(42L), eq(99L), eq(personId), eq(trustedName),
                eq("group:trusted"), eq("ko-KR"), eq(requestId), eq("validate-23"),
                eq("corr-23"), any(ValidateAssistantRequest.class));
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
    void permissionsAndElevatedModeFailClosed() {
        assertThatThrownBy(() -> requirePermission("APP.WORKPLACE:VIEW", UPDATE))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> requireElevated("STANDARD"))
                .isInstanceOf(BaseException.class);
    }
}
