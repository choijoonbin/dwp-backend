package com.dwp.services.auth.config;

import com.dwp.services.auth.security.AuthSessionJwtTokenEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class AuthSessionJwtStartupProbeTest {

    private static final String SECRET =
            "test_secret_key_that_is_at_least_256_bits_long_for_hs256";

    @Test
    void applicationRunnerExecutesTheJwtReadinessProbe() {
        AuthSessionJwtTokenEncoder encoder = new AuthSessionJwtTokenEncoder(
                SECRET, new ObjectMapper().findAndRegisterModules());
        AuthSessionJwtStartupProbe probe = new AuthSessionJwtStartupProbe(encoder);

        assertThatCode(() -> probe.run(null)).doesNotThrowAnyException();
    }
}
