package com.dwp.services.auth.informationreplay;

import com.dwp.services.auth.service.InformationReplayIdentityAuthorityBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
public class InformationReplayConfiguration {
    private static final String PREFIX = "dwp.auth.approval-information-replay.";
    @Bean InformationReplayJson informationReplayJson(ObjectMapper mapper) { return new InformationReplayJson(mapper); }
    @Bean InformationReplayKeys informationReplayKeys(InformationReplayJson json, Environment environment) {
        var forbidden = new ArrayList<String>();
        for (String property : new String[]{"dwp.auth.approval-form-user-proof-jwks", "dwp.auth.approval-workflow-role-proof-jwks",
                "dwp.auth.approval-workflow-role-transport-jwks", "dwp.auth.approval-workflow-role-mapping-private-jwk",
                "dwp.auth.approval-workflow-runtime.owner-trusted-keys", "dwp.auth.approval-workflow-runtime.transport-trusted-keys",
                "dwp.auth.approval-workflow-runtime.attestation-private-key", "dwp.auth.approval-workflow-runtime.attestation-trusted-keys",
                "dwp.auth.approval-policy-impact.owner-public-jwks", "dwp.auth.approval-policy-impact.transport-public-jwks",
                "dwp.auth.approval-policy-impact.attestation-private-jwk", "dwp.auth.approval-policy-impact.attestation-public-jwks",
                "dwp.auth.approval-recovery-proof-jwks", "dwp.auth.step-up.private-key-pem"}) {
            forbidden.add(environment.getProperty(property, ""));
        }
        return new InformationReplayKeys(json, environment.getProperty(PREFIX + "owner-trusted-keys", ""),
                environment.getProperty(PREFIX + "transport-trusted-keys", ""), environment.getProperty(PREFIX + "attestation-private-key", ""),
                environment.getProperty(PREFIX + "attestation-trusted-keys", ""), forbidden.toArray(String[]::new));
    }
    @Bean InformationReplayProofVerifier informationReplayProofVerifier(InformationReplayJson json, InformationReplayKeys keys) {
        return new InformationReplayProofVerifier(json, keys, Clock.systemUTC());
    }
    @Bean InformationReplayReplayStore informationReplayReplayStore(ObjectProvider<StringRedisTemplate> redis) {
        return new InformationReplayReplayStore(redis.getIfAvailable());
    }
    @Bean InformationReplayAttestationIssuer informationReplayAttestationIssuer(InformationReplayKeys keys, InformationReplayJson json) {
        return new InformationReplayAttestationIssuer(keys, json);
    }
    @Bean InformationReplayAuthorityService informationReplayAuthorityService(Environment environment, InformationReplayProofVerifier verifier,
            InformationReplayIdentityAuthorityBridge authority, InformationReplayReplayStore replay, InformationReplayAttestationIssuer issuer, InformationReplayJson json) {
        return new InformationReplayAuthorityService(environment.getProperty(PREFIX + "enabled", Boolean.class, false), verifier, authority, replay, issuer, json);
    }
}
