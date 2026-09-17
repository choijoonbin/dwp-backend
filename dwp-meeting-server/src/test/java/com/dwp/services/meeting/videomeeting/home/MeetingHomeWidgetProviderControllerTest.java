package com.dwp.services.meeting.videomeeting.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestException;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingHomeWidgetProviderControllerTest {
    private static final String TOKEN = "meeting-home-token-1234567890123";
    private final ObjectMapper mapper = new ObjectMapper();
    private final VideoMeetingService service = mock(VideoMeetingService.class);
    private final MeetingHomeWidgetProviderController controller =
            new MeetingHomeWidgetProviderController(service, mapper, TOKEN);

    @Test
    void requiresExactTokenAndOwnedDefinition() throws Exception {
        assertReason(request("wrong-purpose-token-12345678901", "APP.MEETINGS:VIEW"),
                "meetings.next-prep", "HOME_PROVIDER_IDENTITY_INVALID");
        assertReason(request(TOKEN, "APP.MEETINGS:VIEW"),
                "space.change-feed", "HOME_PROVIDER_DEFINITION_NOT_OWNED");
    }

    @Test
    void keepsPermissionAndSourceAclDenialsOutOfStaleEligibility() throws Exception {
        var missing = controller.batch(body("meetings.next-prep"), request(TOKEN, ""));
        assertThat(missing.results().getFirst().state())
                .isEqualTo(HomeWidgetProviderContract.State.FORBIDDEN);
        when(service.home("Asia/Seoul")).thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        var revoked = controller.batch(body("meetings.next-prep"),
                request(TOKEN, "APP.MEETINGS:VIEW"));
        assertThat(revoked.results().getFirst().state())
                .isEqualTo(HomeWidgetProviderContract.State.FORBIDDEN);
        assertThat(revoked.results().getFirst().source().reasonCode())
                .isEqualTo("AUTHORIZATION_MEETING_SOURCE_REVOKED");
    }

    private void assertReason(MockHttpServletRequest request, String definition, String reason)
            throws Exception {
        assertThatThrownBy(() -> controller.batch(body(definition), request))
                .isInstanceOf(HomeWidgetProviderRequestException.class)
                .extracting(failure -> ((HomeWidgetProviderRequestException) failure).reasonCode())
                .isEqualTo(reason);
    }

    private com.fasterxml.jackson.databind.JsonNode body(String definition) throws Exception {
        return mapper.readTree("{\"schemaVersion\":1,\"widgets\":[{"
                + "\"instanceId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"definitionKey\":\"" + definition + "\",\"definitionVersion\":\"1.0.0\","
                + "\"definitionManifestHash\":\"" + "a".repeat(64) + "\","
                + "\"rendererBindingRevision\":\"binding-1\",\"configuration\":{},\"itemLimit\":3}]}" );
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
