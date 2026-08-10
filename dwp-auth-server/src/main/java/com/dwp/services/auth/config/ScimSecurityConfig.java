package com.dwp.services.auth.config;

import com.dwp.services.auth.scim.ScimAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
public class ScimSecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain scimSecurityFilterChain(
            HttpSecurity http,
            ScimAuthenticationFilter authenticationFilter) throws Exception {
        http
                .securityMatcher("/scim/v2/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(authenticationFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
