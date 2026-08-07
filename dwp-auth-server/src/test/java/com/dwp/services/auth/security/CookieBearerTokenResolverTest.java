package com.dwp.services.auth.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class CookieBearerTokenResolverTest {

    private final CookieBearerTokenResolver resolver =
            new CookieBearerTokenResolver("DWP_SESSION");

    @Test
    void resolvesBrowserSessionCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("DWP_SESSION", "cookie-token"));

        assertThat(resolver.resolve(request)).isEqualTo("cookie-token");
    }

    @Test
    void bearerHeaderTakesPrecedenceForApiClients() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer header-token");
        request.setCookies(new Cookie("DWP_SESSION", "cookie-token"));

        assertThat(resolver.resolve(request)).isEqualTo("header-token");
    }
}
