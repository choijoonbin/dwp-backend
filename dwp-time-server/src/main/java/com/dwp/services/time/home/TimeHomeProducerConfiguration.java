package com.dwp.services.time.home;

import java.time.Clock;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Default-off owner wiring. Enabling with missing key material fails at startup. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        name = "dwp.hris.time.home.enabled",
        havingValue = "true",
        matchIfMissing = false)
class TimeHomeProducerConfiguration {

    @Bean("timeHomeClock")
    @ConditionalOnMissingBean(name = "timeHomeClock")
    Clock timeHomeClock() {
        return Clock.systemUTC();
    }

    @Bean
    TimeHomePayloadReferenceCodec timeHomePayloadReferenceCodec(
            @Value("${dwp.hris.time.home.payload-reference-hmac-secret:}") String encodedSecret) {
        if (encodedSecret == null || encodedSecret.isBlank()) {
            throw new IllegalStateException(
                    "Time Home payload-reference HMAC secret is required when enabled");
        }
        byte[] key;
        try {
            key = Base64.getUrlDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(
                    "Time Home payload-reference HMAC secret must be base64url", malformed);
        }
        return new TimeHomePayloadReferenceCodec(key);
    }
}
