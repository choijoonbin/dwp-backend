package com.dwp.services.notification.api;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.domain.NotificationAppSummaryModels.AppNotificationCounter;
import com.dwp.services.notification.domain.NotificationAppSummaryModels.AppNotificationSummary;
import com.dwp.services.notification.domain.NotificationAppSummaryService;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAppSummaryControllerTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42, 900018L, Set.of(), Set.of(), false, "dwp-gateway");
    private static final Instant GENERATED_AT = Instant.parse("2026-08-21T00:30:00Z");

    @AfterEach
    void clearContext() {
        NotificationRequestContext.clear();
    }

    @Test
    void exposesTheCurrentActorsAppSummaryWithoutContentOrIdentityFields() {
        NotificationAppSummaryService service = mock(NotificationAppSummaryService.class);
        AppNotificationSummary summary = new AppNotificationSummary(
                false,
                List.of(),
                List.of(new AppNotificationCounter(
                        "messaging", 6, 2, 0, GENERATED_AT)),
                "128",
                "54",
                GENERATED_AT);
        when(service.summary(ACTOR)).thenReturn(summary);
        NotificationRequestContext.set(ACTOR);
        NotificationAppSummaryController controller =
                new NotificationAppSummaryController(service);

        ApiResponse<AppNotificationSummary> response = controller.byApp();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode json = objectMapper.valueToTree(response.data());

        assertThat(response.success()).isTrue();
        assertThat(json.path("apps").get(0).path("appKey").textValue())
                .isEqualTo("messaging");
        assertThat(json.path("apps").get(0).path("lastActivityAt").textValue())
                .isEqualTo("2026-08-21T00:30:00Z");
        assertThat(json.toString())
                .doesNotContain(
                        "900018",
                        "title",
                        "body",
                        "threadId",
                        "notificationId",
                        "targetRef");
        verify(service).summary(ACTOR);
    }
}
