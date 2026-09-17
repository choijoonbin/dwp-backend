package com.dwp.services.notification.home;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestException;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAppSummaryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationHomeWidgetProviderControllerTest {
    private static final String TOKEN = "notification-home-token-123456789";
    private final ObjectMapper mapper = new ObjectMapper();
    private final NotificationAppSummaryService service = mock(NotificationAppSummaryService.class);
    private final NotificationHomeWidgetProviderController controller =
            new NotificationHomeWidgetProviderController(service, mapper, TOKEN);

    @Test
    void enforcesPurposeIdentityOwnershipAndAcl() throws Exception {
        assertReason("space.change-feed", request(TOKEN, "APP.NOTIFICATIONS:VIEW"),
                "HOME_PROVIDER_DEFINITION_NOT_OWNED");
        assertReason("notification.app-badges", request("wrong-purpose-token-12345678901", "APP.NOTIFICATIONS:VIEW"),
                "HOME_PROVIDER_IDENTITY_INVALID");
        assertThat(controller.batch(body("notification.app-badges"), request(TOKEN, ""))
                .results().getFirst().state()).isEqualTo(HomeWidgetProviderContract.State.FORBIDDEN);
        when(service.summary(any())).thenThrow(new NotificationException(NotificationErrorCode.FORBIDDEN));
        var revoked = controller.batch(body("notification.app-badges"),
                request(TOKEN, "APP.NOTIFICATIONS:VIEW"));
        assertThat(revoked.results().getFirst().source().reasonCode())
                .isEqualTo("AUTHORIZATION_NOTIFICATION_SOURCE_REVOKED");
    }

    private void assertReason(String definition, MockHttpServletRequest request, String reason) throws Exception {
        assertThatThrownBy(() -> controller.batch(body(definition), request))
                .isInstanceOf(HomeWidgetProviderRequestException.class)
                .extracting(failure -> ((HomeWidgetProviderRequestException) failure).reasonCode()).isEqualTo(reason);
    }

    private com.fasterxml.jackson.databind.JsonNode body(String definition) throws Exception {
        return mapper.readTree("{\"schemaVersion\":1,\"widgets\":[{\"instanceId\":"
                + "\"11111111-1111-1111-1111-111111111111\",\"definitionKey\":\"" + definition
                + "\",\"definitionVersion\":\"1.0.0\",\"definitionManifestHash\":\""
                + "a".repeat(64) + "\",\"rendererBindingRevision\":\"binding-1\","
                + "\"configuration\":{},\"itemLimit\":3}]}" );
    }

    private MockHttpServletRequest request(String token, String permissions) {
        var request = new MockHttpServletRequest("POST", HomeWidgetProviderContract.BATCH_PATH);
        request.addHeader(HomeWidgetProviderContract.SERVICE_IDENTITY_HEADER, "dwp-platform-server");
        request.addHeader(HomeWidgetProviderContract.SERVICE_TOKEN_HEADER, token);
        request.addHeader("X-DWP-Tenant-ID", "7"); request.addHeader("X-DWP-User-ID", "11");
        request.addHeader("X-DWP-Permissions", permissions);
        request.addHeader(HomeWidgetProviderContract.AUTHORITY_REVISION_HEADER, "decision-42");
        request.addHeader("X-DWP-Current-Revalidate-At", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        request.addHeader("X-DWP-Home-Deadline-At", OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2));
        return request;
    }
}
