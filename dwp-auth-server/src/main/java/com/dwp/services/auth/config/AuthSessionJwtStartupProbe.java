package com.dwp.services.auth.config;

import com.dwp.services.auth.security.AuthSessionJwtTokenEncoder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Fails application startup if session JWT encoding or verification is unusable. */
@Component
public final class AuthSessionJwtStartupProbe implements ApplicationRunner {

    private final AuthSessionJwtTokenEncoder tokenEncoder;

    public AuthSessionJwtStartupProbe(AuthSessionJwtTokenEncoder tokenEncoder) {
        this.tokenEncoder = tokenEncoder;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        tokenEncoder.verifyStartupReadiness();
    }
}
