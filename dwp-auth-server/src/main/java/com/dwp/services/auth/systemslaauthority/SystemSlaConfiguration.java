package com.dwp.services.auth.systemslaauthority;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class SystemSlaConfiguration {
    private static final String PREFIX = "dwp.auth.approval-system-sla.";
    @Bean SystemSlaJson systemSlaJson(ObjectMapper mapper) { return new SystemSlaJson(mapper); }
    @Bean @Lazy SystemSlaKeys systemSlaKeys(SystemSlaJson json, ConfigurableEnvironment environment) {
        var forbidden = new ArrayList<String>(); var names = new java.util.TreeSet<String>();
        for (var source : environment.getPropertySources()) if (source instanceof EnumerablePropertySource<?> enumerable)
            java.util.Collections.addAll(names, enumerable.getPropertyNames());
        for (String name : names) if (name.startsWith("dwp.") && !name.startsWith(PREFIX)
                && name.matches(".*(key|jwk|jwks|pem).*")) {
            String value = environment.getProperty(name, "");
            if (!value.isBlank() && (value.stripLeading().startsWith("{") || value.startsWith("-----BEGIN"))) forbidden.add(value);
            else if (name.endsWith("key-id") && !value.isBlank()) forbidden.add("kid:" + value);
        }
        return new SystemSlaKeys(json, environment.getProperty(PREFIX + "owner-public-jwks", ""),
                environment.getProperty(PREFIX + "transport-public-jwks", ""), environment.getProperty(PREFIX + "attestation-private-jwk", ""),
                environment.getProperty(PREFIX + "attestation-public-jwks", ""), forbidden);
    }
    @Bean @Lazy SystemSlaProofVerifier systemSlaProofVerifier(SystemSlaJson json, SystemSlaKeys keys) { return new SystemSlaProofVerifier(json, keys, Clock.systemUTC()); }
    @Bean SystemSlaReplayStore systemSlaReplayStore(ObjectProvider<StringRedisTemplate> redis) { return new SystemSlaReplayStore(redis.getIfAvailable(), Clock.systemUTC()); }
    @Bean SystemSlaAttestationIssuer systemSlaAttestationIssuer(SystemSlaJson json, ObjectProvider<SystemSlaKeys> keys) { return new SystemSlaAttestationIssuer(json, keys::getObject, Clock.systemUTC()); }
    @Bean SystemSlaAuthorityService systemSlaAuthorityService(ObjectProvider<SystemSlaProofVerifier> verifier, SystemSlaCurrentAuthority authority,
            SystemSlaReplayStore replay, SystemSlaAttestationIssuer issuer, ConfigurableEnvironment environment) {
        return new SystemSlaAuthorityService(verifier::getObject, authority, replay, issuer, environment.getProperty(PREFIX + "enabled", Boolean.class, false));
    }
}
