package com.dwp.services.approval.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestException;
import com.dwp.services.approval.domain.ApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalHomeWidgetProviderControllerTest {

    private static final String TOKEN = "approval-home-token-123456789012";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApprovalService service = mock(ApprovalService.class);
    private final ApprovalHomeWidgetProviderController controller =
            new ApprovalHomeWidgetProviderController(service, mapper, TOKEN);

    @Test
    void requiresExactPurposeIdentityAndRejectsAmbientBrowserAuthority() throws Exception {
        var wrong = request("gateway-token-123456789012345", "APP.APPROVALS:VIEW");
        assertThatThrownBy(() -> controller.batch(body("approval.focus-queue"), wrong))
                .isInstanceOf(HomeWidgetProviderRequestException.class)
                .extracting(failure -> ((HomeWidgetProviderRequestException) failure).reasonCode())
                .isEqualTo("HOME_PROVIDER_IDENTITY_INVALID");
        var ambient = request(TOKEN, "APP.APPROVALS:VIEW");
        ambient.addHeader("Authorization", "Bearer browser-token");
        assertThatThrownBy(() -> controller.batch(body("approval.focus-queue"), ambient))
                .isInstanceOf(HomeWidgetProviderRequestException.class)
                .extracting(failure -> ((HomeWidgetProviderRequestException) failure).reasonCode())
                .isEqualTo("HOME_PROVIDER_AMBIENT_AUTHORITY_REJECTED");
    }

    @Test
    void returnsForbiddenForMissingPermissionAndOwnerAclRevocation() throws Exception {
        var missing = controller.batch(body("approval.focus-queue"), request(TOKEN, ""));
        assertThat(missing.results().getFirst().state())
                .isEqualTo(HomeWidgetProviderContract.State.FORBIDDEN);
        when(service.home()).thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        var revoked = controller.batch(body("approval.focus-queue"), request(
                TOKEN, "APP.APPROVALS:VIEW,ACTION.APPROVAL_TASK:VIEW"));
        assertThat(revoked.results().getFirst().state())
                .isEqualTo(HomeWidgetProviderContract.State.FORBIDDEN);
        assertThat(revoked.results().getFirst().source().reasonCode())
                .isEqualTo("AUTHORIZATION_APPROVAL_SOURCE_REVOKED");
    }

    @Test
    void rejectsDefinitionsOwnedByAnotherService() throws Exception {
        assertThatThrownBy(() -> controller.batch(
                body("meetings.next-prep"), request(TOKEN, "APP.APPROVALS:VIEW")))
                .isInstanceOf(HomeWidgetProviderRequestException.class)
                .extracting(failure -> ((HomeWidgetProviderRequestException) failure).reasonCode())
                .isEqualTo("HOME_PROVIDER_DEFINITION_NOT_OWNED");
    }

    @Test
    void exposesOnlyTheExactJsonPostAndRejectsDuplicateOrCookieAuthority() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(providerPost()
                        .header(HomeWidgetProviderContract.SERVICE_IDENTITY_HEADER,
                                "duplicate-platform-identity"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode")
                        .value("HOME_PROVIDER_HEADER_DUPLICATED"));

        mvc.perform(providerPost().header("Cookie", "JSESSIONID=ambient-browser-session"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasonCode")
                        .value("HOME_PROVIDER_AMBIENT_AUTHORITY_REJECTED"));

        mvc.perform(post(HomeWidgetProviderContract.BATCH_PATH)
                        .contentType("text/plain")
                        .content(body("approval.focus-queue").toString()))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(get(HomeWidgetProviderContract.BATCH_PATH))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post(HomeWidgetProviderContract.BATCH_PATH + "/suffix")
                        .contentType("application/json")
                        .content(body("approval.focus-queue").toString()))
                .andExpect(status().isNotFound());
    }

    private com.fasterxml.jackson.databind.JsonNode body(String definition) throws Exception {
        return mapper.readTree("{\"schemaVersion\":1,\"widgets\":[{"
                + "\"instanceId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"definitionKey\":\"" + definition + "\","
                + "\"definitionVersion\":\"1.0.0\","
                + "\"definitionManifestHash\":\"" + "a".repeat(64) + "\","
                + "\"rendererBindingRevision\":\"binding-1\","
                + "\"configuration\":{},\"itemLimit\":3}]}" );
    }

    private MockHttpServletRequest request(String token, String permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", HomeWidgetProviderContract.BATCH_PATH);
        request.addHeader(HomeWidgetProviderContract.SERVICE_IDENTITY_HEADER, "dwp-platform-server");
        request.addHeader(HomeWidgetProviderContract.SERVICE_TOKEN_HEADER, token);
        request.addHeader("X-DWP-Tenant-ID", "7");
        request.addHeader("X-DWP-User-ID", "11");
        request.addHeader("X-DWP-Permissions", permissions);
        request.addHeader(HomeWidgetProviderContract.AUTHORITY_REVISION_HEADER, "decision-42");
        request.addHeader("X-DWP-Current-Revalidate-At", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        request.addHeader("X-DWP-Home-Deadline-At", OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2));
        return request;
    }

    private MockHttpServletRequestBuilder providerPost() throws Exception {
        return post(HomeWidgetProviderContract.BATCH_PATH)
                .contentType("application/json")
                .content(body("approval.focus-queue").toString())
                .header(HomeWidgetProviderContract.SERVICE_IDENTITY_HEADER,
                        "dwp-platform-server")
                .header(HomeWidgetProviderContract.SERVICE_TOKEN_HEADER, TOKEN)
                .header("X-DWP-Tenant-ID", "7")
                .header("X-DWP-User-ID", "11")
                .header("X-DWP-Permissions",
                        "APP.APPROVALS:VIEW,ACTION.APPROVAL_TASK:VIEW")
                .header(HomeWidgetProviderContract.AUTHORITY_REVISION_HEADER, "decision-42")
                .header("X-DWP-Current-Revalidate-At",
                        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1).toString())
                .header("X-DWP-Home-Deadline-At",
                        OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2).toString());
    }
}
