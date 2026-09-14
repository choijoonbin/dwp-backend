package com.dwp.services.auth.approvalpolicyimpact;

import com.dwp.services.auth.service.ApprovalPolicyImpactIdentityAuthorityBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class PolicyImpactConfiguration {
    @Bean PolicyImpactJson policyImpactJson(ObjectMapper mapper) { return new PolicyImpactJson(mapper); }
    @Bean @Lazy PolicyImpactKeys policyImpactKeys(PolicyImpactJson json, Environment environment) {
        var forbidden = new ArrayList<String>();
        for (String property : new String[]{"dwp.auth.approval-workflow-runtime.owner-trusted-keys",
                "dwp.auth.approval-workflow-runtime.transport-trusted-keys", "dwp.auth.approval-workflow-runtime.attestation-private-key",
                "dwp.auth.approval-workflow-runtime.attestation-trusted-keys", "dwp.auth.approval-form-user-proof-jwks",
                "dwp.auth.approval-workflow-role-proof-jwks", "dwp.auth.approval-workflow-role-transport-jwks",
                "dwp.auth.approval-workflow-role-mapping-private-jwk", "dwp.auth.step-up.private-key-pem"})
            forbidden.add(environment.getProperty(property, ""));
        String stepUpKid = environment.getProperty("dwp.auth.step-up.key-id", "");
        if (!stepUpKid.isBlank()) forbidden.add("kid:" + stepUpKid);
        String prefix = "dwp.auth.approval-policy-impact.";
        return new PolicyImpactKeys(json, environment.getProperty(prefix + "owner-public-jwks", ""),
                environment.getProperty(prefix + "transport-public-jwks", ""),
                environment.getProperty(prefix + "attestation-private-jwk", ""),
                environment.getProperty(prefix + "attestation-public-jwks", ""), forbidden);
    }
    @Bean @Lazy PolicyImpactProofVerifier policyImpactProofVerifier(PolicyImpactJson json, PolicyImpactKeys keys) {
        return new PolicyImpactProofVerifier(json, keys, Clock.systemUTC());
    }
    @Bean PolicyImpactReplayStore policyImpactReplayStore(ObjectProvider<StringRedisTemplate> redis) {
        return new PolicyImpactReplayStore(redis.getIfAvailable(), Clock.systemUTC());
    }
    @Bean PolicyImpactAuthorityIssuer policyImpactAuthorityIssuer(PolicyImpactJson json, ObjectProvider<PolicyImpactKeys> keys) {
        return new PolicyImpactAuthorityIssuer(json, keys::getObject, Clock.systemUTC());
    }
    @Bean PolicyImpactAuthorityService policyImpactAuthorityService(ObjectProvider<PolicyImpactProofVerifier> verifier,
            ApprovalPolicyImpactIdentityAuthorityBridge authority, PolicyImpactReplayStore replay, PolicyImpactAuthorityIssuer issuer,
            Environment environment) {
        return new PolicyImpactAuthorityService(verifier::getObject, authority, replay, issuer,
                environment.getProperty("dwp.auth.approval-policy-impact.enabled", Boolean.class, false));
    }
}
