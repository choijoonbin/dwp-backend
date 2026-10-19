package com.dwp.services.auth.hrisperformancefeature;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Exact dedicated endpoint; production key and durable replay providers remain unbound. */
@Configuration
public class PerformanceFeatureSecurityConfig {
    @Bean @Order(0)
    SecurityFilterChain performanceFeatureSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher(PerformanceFeatureContract.PATH)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The controller verifies the dedicated body-bound workload proof before every read.
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, PerformanceFeatureContract.PATH)
                        .permitAll().anyRequest().denyAll()).build();
    }

    @Bean
    PerformanceFeatureWorkloadVerifier performanceFeatureWorkloadVerifier(ObjectMapper mapper,
            ObjectProvider<PerformanceFeatureWorkloadVerifier.SigningKeys> keys,
            ObjectProvider<PerformanceFeatureWorkloadVerifier.ReplayStore> replay) {
        return new PerformanceFeatureWorkloadVerifier(mapper, Clock.systemUTC(),
                keyId -> {
                    var providers = keys.orderedStream().toList();
                    return providers.size() == 1 ? providers.getFirst().resolve(keyId) : null;
                }, (jti, nonce, tenant, expiry) -> {
                    var providers = replay.orderedStream().toList();
                    return providers.size() == 1 && providers.getFirst().reserve(jti, nonce, tenant, expiry);
                });
    }
}
