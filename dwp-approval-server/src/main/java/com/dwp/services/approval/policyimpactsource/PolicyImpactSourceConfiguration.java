package com.dwp.services.approval.policyimpactsource;

import com.dwp.services.approval.domain.ApprovalPolicyImpactRuntimeBridge;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.policyimpact.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class PolicyImpactSourceConfiguration {
    @Bean PolicyImpactSourceJson policyImpactSourceJson(ObjectMapper mapper) { return new PolicyImpactSourceJson(mapper); }
    @Bean @Lazy PolicyImpactSourceKeys policyImpactSourceKeys(PolicyImpactSourceJson json, Environment env) {
        List<String> forbidden = List.of("dwp.approval.workflow-runtime-authority.auth-attestation-trusted-keys",
                "dwp.approval.workflow-runtime-authority.prohibited-trusted-keys", "dwp.approval.form-user-source-private-jwk",
                "dwp.approval.workflow-runtime-authority.owner-private-key", "dwp.approval.workflow-runtime-authority.transport-private-key",
                "dwp.approval.step-up.public-key-pem");
        var prohibited = new java.util.ArrayList<>(forbidden.stream().map(key -> env.getProperty(key, "")).toList());
        for (String property : List.of("dwp.approval.workflow-runtime-authority.owner-key-id", "dwp.approval.workflow-runtime-authority.transport-key-id",
                "dwp.approval.step-up.key-id")) {
            String kid = env.getProperty(property, ""); if (!kid.isBlank()) prohibited.add("kid:" + kid);
        }
        return new PolicyImpactSourceKeys(json, env.getProperty("dwp.approval.policy-impact.source.owner-private-jwk"),
                env.getProperty("dwp.approval.policy-impact.source.transport-private-jwk"), env.getProperty("dwp.approval.policy-impact.source.attestation-public-jwks"),
                prohibited);
    }
    @Bean @Lazy PolicyImpactSourceProofIssuer policyImpactSourceProofIssuer(PolicyImpactSourceKeys keys, PolicyImpactSourceJson json) {
        return new PolicyImpactSourceProofIssuer(keys, json, Clock.systemUTC());
    }
    @Bean @Lazy AuthApprovalPolicyImpactAuthorityClient authApprovalPolicyImpactAuthorityClient(PolicyImpactSourceKeys keys, PolicyImpactSourceJson json, Environment env) {
        String base = env.getProperty("dwp.approval.policy-impact.source.auth-base-url");
        try {
            if (base == null || base.endsWith("/")) throw PolicyImpactSourceJson.unavailable();
            return new AuthApprovalPolicyImpactAuthorityClient(URI.create(base + PolicyImpactSourceProtocol.PATH),
                    new PolicyImpactSourceAttestationVerifier(keys, json, Clock.systemUTC()));
        } catch (RuntimeException invalid) { throw PolicyImpactSourceJson.unavailable(); }
    }
    @Bean PolicyImpactSourceService policyImpactSourceService(ApprovalIdentityDirectory identities, NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper, PolicyImpactSourceJson json, PlatformTransactionManager manager, Environment env,
            ObjectProvider<PolicyImpactSourceProofIssuer> issuer, ObjectProvider<AuthApprovalPolicyImpactAuthorityClient> client) {
        Clock clock = Clock.systemUTC();
        var runtime = new ApprovalPolicyImpactRuntimeBridge(jdbc, mapper);
        var evaluator = new ApprovalPolicyImpactEvaluator(runtime);
        return new PolicyImpactSourceService(new PolicyImpactInstalledContext(identities, json, clock), new PolicyImpactSourceHeadReader(jdbc, mapper, evaluator),
                issuer::getObject, client::getObject, new ApprovalPolicyImpactRepository(jdbc, mapper), runtime,
                new TransactionTemplate(manager), clock, env.getProperty("dwp.approval.policy-impact.source.enabled", Boolean.class, false));
    }
}
