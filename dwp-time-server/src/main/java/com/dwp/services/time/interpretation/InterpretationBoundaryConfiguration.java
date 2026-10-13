package com.dwp.services.time.interpretation;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Secure, default-off construction for the IAQ-041 cursor boundary. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.hris.time.interpretation.enabled",
        havingValue = "true",
        matchIfMissing = false)
class InterpretationBoundaryConfiguration {

    @Bean
    InterpretationIaProjectionService interpretationIaProjectionService(
            @Value("${dwp.hris.time.interpretation.cursor-secret:}") String cursorSecret) {
        return new InterpretationIaProjectionService(Clock.systemUTC(), cursorSecret);
    }
}
