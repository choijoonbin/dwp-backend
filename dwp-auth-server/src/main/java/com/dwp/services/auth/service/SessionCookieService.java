package com.dwp.services.auth.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class SessionCookieService {

    private final String cookieName;
    private final boolean secure;
    private final String sameSite;

    public SessionCookieService(
            @Value("${dwp.security.session.cookie-name:DWP_SESSION}") String cookieName,
            @Value("${dwp.security.session.cookie-secure:false}") boolean secure,
            @Value("${dwp.security.session.same-site:Lax}") String sameSite) {
        this.cookieName = cookieName;
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public void write(HttpServletResponse response, String token, long maxAgeSeconds) {
        ResponseCookie cookie = baseCookie(token)
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = baseCookie("")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/");
    }
}
