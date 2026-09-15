package com.dwp.services.approval.workflowplanning;

import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class WorkflowPlanningConfiguration {
    private static final String PREFIX="dwp.approval.workflow-planning.";
    @Bean WorkflowPlanningInstalledContext workflowPlanningInstalledContext() {return new WorkflowPlanningInstalledContext();}
    @Bean @Lazy WorkflowPlanningRuntime workflowPlanningRuntime(Environment environment) {
        var registered=new ArrayList<String>();
        for(String property:new String[]{"dwp.approval.workflow-runtime-authority.owner-private-key","dwp.approval.workflow-runtime-authority.transport-private-key",
                "dwp.approval.workflow-runtime-authority.auth-attestation-trusted-keys","dwp.approval.workflow-runtime-authority.prohibited-trusted-keys",
                "dwp.approval.information-replay.owner-private-key","dwp.approval.information-replay.transport-private-key",
                "dwp.approval.information-replay.auth-attestation-trusted-keys","dwp.approval.information-replay.prohibited-trusted-keys",
                "dwp.approval.form-user-source-private-jwk","dwp.approval.policy-impact.source.owner-private-jwk",
                "dwp.approval.policy-impact.source.transport-private-jwk","dwp.approval.policy-impact.source.attestation-public-jwks",
                "dwp.approval.step-up.public-key-pem","dwp.approval.internal-signatures.signing-private-jwk","dwp.approval.internal-signatures.authority-public-jwks"})
            registered.add(environment.getProperty(property));
        for(String property:new String[]{"dwp.approval.workflow-runtime-authority.owner-key-id","dwp.approval.workflow-runtime-authority.transport-key-id","dwp.approval.step-up.key-id"}) {
            String id=environment.getProperty(property);if(id!=null && !id.isBlank()) registered.add("kid:"+id);
        }
        var keys=new WorkflowPlanningKeys(WorkflowPlanningKeys.privateKey(environment.getProperty(PREFIX+"owner-private-key")),
                WorkflowPlanningKeys.privateKey(environment.getProperty(PREFIX+"transport-private-key")),environment.getProperty(PREFIX+"auth-trusted-keys"),
                environment.getProperty(PREFIX+"prohibited-key-catalogue"),registered.toArray(String[]::new));
        var clock=Clock.systemUTC();
        return new WorkflowPlanningRuntime(new WorkflowPlanningProofIssuer(keys,clock),
                new WorkflowPlanningAuthorityClient(URI.create(environment.getProperty(PREFIX+"endpoint","http://localhost:8001"+WorkflowPlanningProtocol.PATH)),new WorkflowPlanningAttestationVerifier(keys,clock)));
    }
    @Bean WorkflowPlanningFacade workflowPlanningFacade(Environment environment,ObjectProvider<WorkflowPlanningRuntime> runtime,WorkflowPlanningInstalledContext installed,
            NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager manager,ApprovalWorkAuthority work) {
        return new WorkflowPlanningFacade(environment.getProperty(PREFIX+"enabled",Boolean.class,false),runtime::getObject,installed,jdbc,mapper,manager,work);
    }
    @Bean WorkflowPlanningReadiness workflowPlanningReadiness(Environment environment,ObjectProvider<WorkflowPlanningRuntime> runtime) {
        return new WorkflowPlanningReadiness(environment.getProperty(PREFIX+"enabled",Boolean.class,false),runtime::getObject);
    }
}
