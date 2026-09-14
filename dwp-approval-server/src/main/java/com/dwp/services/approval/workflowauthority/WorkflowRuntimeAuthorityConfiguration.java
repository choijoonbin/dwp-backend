package com.dwp.services.approval.workflowauthority;

import java.net.URI;
import java.time.Clock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="dwp.approval.workflow-runtime-authority",name="enabled",havingValue="true")
public class WorkflowRuntimeAuthorityConfiguration {
    @Bean WorkflowRuntimeJson workflowRuntimeJson() { return new WorkflowRuntimeJson(); }
    @Bean WorkflowRuntimeKeys workflowRuntimeKeys(
            @Value("${dwp.approval.workflow-runtime-authority.owner-key-id:}") String ownerId,
            @Value("${dwp.approval.workflow-runtime-authority.owner-private-key:}") String owner,
            @Value("${dwp.approval.workflow-runtime-authority.transport-key-id:}") String transportId,
            @Value("${dwp.approval.workflow-runtime-authority.transport-private-key:}") String transport,
            @Value("${dwp.approval.workflow-runtime-authority.auth-attestation-trusted-keys:}") String trust,
            @Value("${dwp.approval.workflow-runtime-authority.prohibited-trusted-keys:}") String prohibited) {
        return new WorkflowRuntimeKeys(WorkflowRuntimeKeys.privateKey(ownerId,owner),WorkflowRuntimeKeys.privateKey(transportId,transport),trust,prohibited);
    }
    @Bean WorkflowRuntimeProofIssuer workflowRuntimeProofIssuer(WorkflowRuntimeKeys keys,WorkflowRuntimeJson json) {
        return new WorkflowRuntimeProofIssuer(keys,json,Clock.systemUTC());
    }
    @Bean WorkflowRuntimeAttestationVerifier workflowRuntimeAttestationVerifier(WorkflowRuntimeKeys keys,WorkflowRuntimeJson json) {
        return new WorkflowRuntimeAttestationVerifier(keys,json,Clock.systemUTC());
    }
    @Bean WorkflowRuntimeAuthorityClient workflowRuntimeAuthorityClient(
            @Value("${dwp.approval.workflow-runtime-authority.endpoint:}") String endpoint,WorkflowRuntimeAttestationVerifier verifier) {
        return new WorkflowRuntimeAuthorityClient(URI.create(endpoint),verifier);
    }
    @Bean WorkflowRuntimeInformationAdmission workflowRuntimeInformationAdmission(ObjectProvider<HttpServletRequest> requests,
            WorkflowRuntimeJson json,WorkflowRuntimeProofIssuer issuer,WorkflowRuntimeAuthorityClient client) {
        return new WorkflowRuntimeInformationAdmission(requests,new WorkflowRuntimeActionContext(),json,issuer,client);
    }
    @Bean WorkflowRuntimeCurrentSource workflowRuntimeCurrentSource(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
            ObjectProvider<HttpServletRequest> requests,WorkflowRuntimeProofIssuer issuer,WorkflowRuntimeAuthorityClient client) {
        return new WorkflowRuntimeCurrentSource(jdbc,requests,new WorkflowRuntimeActionContext(),issuer,client);
    }
}
