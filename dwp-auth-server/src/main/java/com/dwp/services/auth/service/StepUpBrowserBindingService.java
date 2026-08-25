package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public final class StepUpBrowserBindingService {

    public static final String COOKIE_NAME = "DWP_STEP_UP_BROWSER";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final boolean secure;

    public StepUpBrowserBindingService(
            @Value("${dwp.security.session.cookie-secure:false}") boolean secure) {
        this.secure = secure;
    }

    public Binding create(HttpServletResponse response) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/api/auth/oidc");
        cookie.setMaxAge(600);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        return new Binding(hash(token));
    }

    public void require(HttpServletRequest request, String expectedHash) {
        String token = cookie(request);
        if (token == null || expectedHash == null || !MessageDigest.isEqual(
                hash(token).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
    }

    public void clear(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/api/auth/oidc");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie value : request.getCookies()) {
            if (COOKIE_NAME.equals(value.getName())) return value.getValue();
        }
        return null;
    }

    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Binding(String hash) {
    }
}
