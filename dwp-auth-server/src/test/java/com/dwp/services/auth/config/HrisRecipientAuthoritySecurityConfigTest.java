package com.dwp.services.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class HrisRecipientAuthoritySecurityConfigTest {

    private static final String TOKEN = "recipient-authority-secret";
    private static final String BODY = """
            {"requestId":"11111111-1111-4111-8111-111111111111",
             "tenantId":17,
             "personPublicId":"22222222-2222-4222-8222-222222222222",
             "purpose":"HCM.EMPLOYEE_CHANGE_APPLIED_NOTIFICATION"}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsOnlyTheExactDedicatedPeoplePostAndPreservesItsValidatedBody()
            throws Exception {
        var filter = filter(TOKEN);
        MockHttpServletRequest request = validRequest(
                "POST", HrisRecipientAuthoritySecurityConfig.PATH, BODY);

        Invocation invocation = invoke(filter, request);

        assertThat(invocation.invoked()).isTrue();
        assertThat(invocation.response().getStatus()).isEqualTo(200);
        assertThat(invocation.response().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void rejectsMissingBlankPaddedDuplicateMixedOrCrossServiceCredentials()
            throws Exception {
        MockHttpServletRequest missing = request(BODY);
        MockHttpServletRequest blank = request(BODY);
        blank.addHeader(HrisRecipientAuthoritySecurityConfig.TOKEN_HEADER, " ");
        MockHttpServletRequest padded = request(BODY);
        padded.addHeader(HrisRecipientAuthoritySecurityConfig.TOKEN_HEADER, " " + TOKEN);
        MockHttpServletRequest duplicate = request(BODY);
        duplicate.addHeader(HrisRecipientAuthoritySecurityConfig.TOKEN_HEADER, TOKEN);
        duplicate.addHeader(HrisRecipientAuthoritySecurityConfig.TOKEN_HEADER, TOKEN);
        MockHttpServletRequest duplicateIdentity = request(BODY);
        duplicateIdentity.addHeader(HrisRecipientAuthoritySecurityConfig.TOKEN_HEADER, TOKEN);
        duplicateIdentity.addHeader(
                HrisRecipientAuthoritySecurityConfig.SERVICE_IDENTITY_HEADER,
                HrisRecipientAuthoritySecurityConfig.PEOPLE_SERVICE_IDENTITY);
        MockHttpServletRequest mixed = request(BODY);
        mixed.addHeader(HrisRecipientAuthoritySecurityConfig.TOKEN_HEADER, TOKEN);
        mixed.addHeader("X-DWP-Identity-Sync-Token", TOKEN);
        MockHttpServletRequest bearer = request(BODY);
        bearer.addHeader(HrisRecipientAuthoritySecurityConfig.TOKEN_HEADER, TOKEN);
        bearer.addHeader("Authorization", "Bearer borrowed");
        MockHttpServletRequest crossService = request(BODY);
        crossService.addHeader(HrisRecipientAuthoritySecurityConfig.TOKEN_HEADER, TOKEN);
        crossService.removeHeader(
                HrisRecipientAuthoritySecurityConfig.SERVICE_IDENTITY_HEADER);
        crossService.addHeader(
                HrisRecipientAuthoritySecurityConfig.SERVICE_IDENTITY_HEADER,
                "dwp-notification-server");
        MockHttpServletRequest cookie = request(BODY);
        cookie.addHeader(HrisRecipientAuthoritySecurityConfig.TOKEN_HEADER, TOKEN);
        cookie.setCookies(new Cookie("DWP_SESSION", "borrowed"));

        for (MockHttpServletRequest denied : List.of(
                missing, blank, padded, duplicate, duplicateIdentity, mixed, bearer,
                crossService, cookie)) {
            Invocation invocation = invoke(filter(TOKEN), denied);
            assertThat(invocation.invoked()).isFalse();
            assertThat(invocation.response().getStatus()).isEqualTo(401);
            assertThat(invocation.response().getHeader("Cache-Control"))
                    .isEqualTo("no-store");
        }

        Invocation blankConfiguration = invoke(filter(""), validRequest(
                "POST", HrisRecipientAuthoritySecurityConfig.PATH, BODY));
        Invocation paddedConfiguration = invoke(filter(" " + TOKEN), validRequest(
                "POST", HrisRecipientAuthoritySecurityConfig.PATH, BODY));
        assertThat(blankConfiguration.response().getStatus()).isEqualTo(401);
        assertThat(paddedConfiguration.response().getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsDedicatedCredentialOnWrongMethodPathQueryOrMediaTypeBeforeController()
            throws Exception {
        MockHttpServletRequest wrongMethod = validRequest(
                "GET", HrisRecipientAuthoritySecurityConfig.PATH, BODY);
        MockHttpServletRequest wrongPath = validRequest(
                "POST", "/internal/auth/v1/product-surface-authority/evaluate", BODY);
        MockHttpServletRequest suffix = validRequest(
                "POST", HrisRecipientAuthoritySecurityConfig.PATH + "/extra", BODY);
        MockHttpServletRequest query = validRequest(
                "POST", HrisRecipientAuthoritySecurityConfig.PATH, BODY);
        query.setQueryString("expand=true");
        MockHttpServletRequest media = validRequest(
                "POST", HrisRecipientAuthoritySecurityConfig.PATH, BODY);
        media.setContentType(MediaType.TEXT_PLAIN_VALUE);

        for (MockHttpServletRequest denied : List.of(
                wrongMethod, wrongPath, suffix, query, media)) {
            Invocation invocation = invoke(filter(TOKEN), denied);
            assertThat(invocation.invoked()).isFalse();
            assertThat(invocation.response().getStatus()).isEqualTo(403);
        }
    }

    @Test
    void rejectsUnknownDuplicateMissingNullCoercedAndNonCanonicalRequestFields()
            throws Exception {
        List<String> deniedBodies = List.of(
                BODY.replace("}", ",\"extra\":true}"),
                BODY.replace("\"tenantId\":17", "\"tenantId\":17,\"tenantId\":17"),
                BODY.replace("\"tenantId\":17,", ""),
                BODY.replace("\"tenantId\":17", "\"tenantId\":null"),
                BODY.replace("\"tenantId\":17", "\"tenantId\":\"17\""),
                BODY.replace("\"tenantId\":17", "\"tenantId\":17.0"),
                BODY.replace(
                        "11111111-1111-4111-8111-111111111111",
                        "11111111-1111-4111-8111-11111111111A"),
                BODY.replace(
                        "HCM.EMPLOYEE_CHANGE_APPLIED_NOTIFICATION",
                        "HCM.EMPLOYEE_CHANGE_CREATED_NOTIFICATION"),
                BODY + "{}",
                "[]");

        for (String deniedBody : deniedBodies) {
            Invocation invocation = invoke(filter(TOKEN), validRequest(
                    "POST", HrisRecipientAuthoritySecurityConfig.PATH, deniedBody));
            assertThat(invocation.invoked()).isFalse();
            assertThat(invocation.response().getStatus()).isEqualTo(400);
            assertThat(invocation.response().getHeader("Cache-Control"))
                    .isEqualTo("no-store");
        }
    }

    private HrisRecipientAuthoritySecurityConfig.RecipientAuthorityFilter filter(
            String token) {
        return new HrisRecipientAuthoritySecurityConfig.RecipientAuthorityFilter(
                token, objectMapper);
    }

    private MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", HrisRecipientAuthoritySecurityConfig.PATH);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader(
                HrisRecipientAuthoritySecurityConfig.SERVICE_IDENTITY_HEADER,
                HrisRecipientAuthoritySecurityConfig.PEOPLE_SERVICE_IDENTITY);
        return request;
    }

    private MockHttpServletRequest validRequest(String method, String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader(
                HrisRecipientAuthoritySecurityConfig.SERVICE_IDENTITY_HEADER,
                HrisRecipientAuthoritySecurityConfig.PEOPLE_SERVICE_IDENTITY);
        request.addHeader(HrisRecipientAuthoritySecurityConfig.TOKEN_HEADER, TOKEN);
        return request;
    }

    private Invocation invoke(
            HrisRecipientAuthoritySecurityConfig.RecipientAuthorityFilter filter,
            MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(request, response, (wrapped, servletResponse) -> invoked.set(true));
        return new Invocation(response, invoked.get());
    }

    private record Invocation(MockHttpServletResponse response, boolean invoked) {
    }
}
