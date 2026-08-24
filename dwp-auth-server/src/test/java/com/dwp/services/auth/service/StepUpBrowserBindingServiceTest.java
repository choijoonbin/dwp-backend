package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepUpBrowserBindingServiceTest {

    @Test
    void createsAHostOnlySecureHttpOnlyShortLivedBinding() {
        StepUpBrowserBindingService service = new StepUpBrowserBindingService(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        StepUpBrowserBindingService.Binding binding = service.create(response);

        Cookie cookie = response.getCookie(StepUpBrowserBindingService.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getPath()).isEqualTo("/api/auth/oidc");
        assertThat(cookie.getMaxAge()).isEqualTo(600);
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(cookie.getValue()).isNotEqualTo(binding.hash());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(cookie);
        service.require(request, binding.hash());
    }

    @Test
    void rejectsMissingOrWrongBrowserBindingAndClearsTheCookie() {
        StepUpBrowserBindingService service = new StepUpBrowserBindingService(true);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> service.require(request, "expected-hash"))
                .isInstanceOf(BaseException.class);
        request.setCookies(new Cookie(StepUpBrowserBindingService.COOKIE_NAME, "wrong-token"));
        assertThatThrownBy(() -> service.require(request, "expected-hash"))
                .isInstanceOf(BaseException.class);

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.clear(response);
        Cookie cleared = response.getCookie(StepUpBrowserBindingService.COOKIE_NAME);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(cleared.isHttpOnly()).isTrue();
        assertThat(cleared.getSecure()).isTrue();
    }
}
