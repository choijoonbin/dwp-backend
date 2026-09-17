package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HomePersonalizationExceptionHandlerTest {

    @Test
    void returnsTheLatestViewAndSubmittedDraftInTheConflictEnvelope() {
        HomeViewDtos.HomeViewResponse latest = new HomeViewDtos.HomeViewResponse(
                java.util.UUID.randomUUID(), "default", "workspace-home", "CLASSIC",
                "Server home", true, true, 5, null, 8L,
                null, null, Map.of());
        HomeViewDtos.HomeViewConflictResponse conflict =
                new HomeViewDtos.HomeViewConflictResponse(
                        "UPDATE_VIEW", 7L, 8L, Map.of("name", "My draft"), latest,
                        null, null, null, List.of("name", "layout"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", "wave5-conflict");

        var response = new HomePersonalizationExceptionHandler().conflict(
                new HomeViewConflictException(conflict), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode())
                .isEqualTo(ErrorCode.HOME_VIEW_VERSION_CONFLICT.getCode());
        assertThat(response.getBody().getCorrelationId()).isEqualTo("wave5-conflict");
        assertThat(response.getBody().getData()).isEqualTo(conflict);
    }
}
