package com.dwp.services.approval.informationreplay;

import com.dwp.services.approval.domain.ApprovalInformationReceiptSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
public class InformationReplayConfiguration {
    @Bean ApprovalInformationReceiptSource informationReceiptSource(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper) {
        return new ApprovalInformationReceiptSource(jdbc,mapper);
    }
    @Bean InformationReceiptInstalledContext informationReceiptInstalledContext() {return new InformationReceiptInstalledContext();}
    @Bean @Lazy InformationReplayRuntime informationReplayRuntime(
            @Value("${dwp.approval.information-replay.owner-private-key:}") String owner,
            @Value("${dwp.approval.information-replay.transport-private-key:}") String transport,
            @Value("${dwp.approval.information-replay.auth-attestation-trusted-keys:}") String auth,
            @Value("${dwp.approval.information-replay.prohibited-trusted-keys:}") String prohibited,
            @Value("${dwp.approval.information-replay.endpoint:}") String endpoint,Environment environment) {
        var forbidden=new java.util.ArrayList<String>();
        for(String property:java.util.List.of("dwp.approval.workflow-runtime-authority.owner-private-key",
                "dwp.approval.workflow-runtime-authority.transport-private-key","dwp.approval.workflow-runtime-authority.auth-attestation-trusted-keys",
                "dwp.approval.workflow-runtime-authority.prohibited-trusted-keys","dwp.approval.form-user-source-private-jwk",
                "dwp.approval.policy-impact.source.owner-private-jwk","dwp.approval.policy-impact.source.transport-private-jwk",
                "dwp.approval.policy-impact.source.attestation-public-jwks","dwp.approval.step-up.public-key-pem",
                "dwp.approval.internal-signatures.signing-private-jwk","dwp.approval.internal-signatures.authority-public-jwks"))
            forbidden.add(environment.getProperty(property,""));
        for(String property:java.util.List.of("dwp.approval.workflow-runtime-authority.owner-key-id",
                "dwp.approval.workflow-runtime-authority.transport-key-id","dwp.approval.step-up.key-id")) {
            String id=environment.getProperty(property,"");if(!id.isBlank()) forbidden.add("kid:"+id);
        }
        var keys=new InformationReplayKeys(InformationReplayKeys.privateKey(owner),InformationReplayKeys.privateKey(transport),auth,prohibited,forbidden.toArray(String[]::new));
        var issuer=new InformationReplayProofIssuer(keys,Clock.systemUTC());var verifier=new InformationReplayAttestationVerifier(keys,Clock.systemUTC());
        try {return new InformationReplayRuntime(issuer,new InformationReplayAuthorityClient(URI.create(endpoint),verifier));}
        catch(IllegalArgumentException invalidEndpoint) {throw com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.unavailable();}
    }
    @Bean InformationReceiptFacade informationReceiptFacade(@Value("${dwp.approval.information-replay.enabled:false}") boolean enabled,
            ObjectProvider<InformationReplayRuntime> runtime,InformationReceiptInstalledContext installed,ApprovalInformationReceiptSource source,
            PlatformTransactionManager transactions) {
        return new InformationReceiptFacade(enabled,runtime::getObject,installed,source,transactions,Clock.systemUTC());
    }
}
