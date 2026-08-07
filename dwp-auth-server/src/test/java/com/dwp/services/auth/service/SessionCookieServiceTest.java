package com.dwp.services.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCookieServiceTest {

    private final SessionCookieService service =
            new SessionCookieService("DWP_SESSION", true, "Lax");

    @Test
    void writesAnHttpOnlySecureSessionCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.write(response, "signed-token", 3600);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("DWP_SESSION=signed-token")
                .contains("Path=/")
                .contains("Max-Age=3600")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
    }

    @Test
    void clearsTheSessionCookieImmediately() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.clear(response);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("DWP_SESSION=")
                .contains("Max-Age=0");
    }
}
