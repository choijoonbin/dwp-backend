package com.dwp.services.approval.domain;

import com.dwp.services.approval.systemslaauthority.*;
import com.dwp.services.approval.integration.ApprovalIntegrationPublisher;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
public class ApprovalSystemSlaProducerConfiguration {
    @Bean SystemSlaProducerSource systemSlaProducerSource(SystemSlaCurrentSource current,ApprovalSystemSlaNativeSource nativeSource,
            ObjectProvider<SystemSlaSourceProofIssuer> issuer,ObjectProvider<AuthApprovalSystemSlaAuthorityClient> client,SystemSlaJson json,
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        return new SystemSlaProducerSource(current,nativeSource,issuer::getObject,client::getObject,json,Clock.systemUTC(),new SystemSlaSourceWitnessJournal(jdbc,json));
    }
    @Bean Object approvalSystemSlaProducerBinding(ApprovalWorkflowQuorumFacade facade,SystemSlaProducerSource source,
            ObjectProvider<SystemSlaSourceKeys> keys,ObjectProvider<AuthApprovalSystemSlaAuthorityClient> client,Environment env) {
        facade.bindSlaInitializer(runtime -> {
            if (!env.getProperty("dwp.approval.system-sla.source.enabled",Boolean.class,false)) throw SystemSlaJson.unavailable();
            keys.getObject(); client.getObject(); bind(runtime,source);
        });
        return new Object();
    }
    @Bean
    @ConditionalOnProperty(name="dwp.approval.workflow-quorum.sla.enabled",havingValue="true")
    ApprovalSystemSlaRuntimeReadiness approvalSystemSlaRuntimeReadiness(Environment environment,
            ObjectProvider<SystemSlaSourceKeys> sourceKeys,ObjectProvider<SystemSlaNotificationKeys> notificationKeys,
            ObjectProvider<AuthApprovalSystemSlaAuthorityClient> authorityClients,
            ObjectProvider<ApprovalIntegrationPublisher> publishers,KafkaAdmin kafkaAdmin) {
        return new ApprovalSystemSlaRuntimeReadiness(environment,() -> {
            sourceKeys.getObject();notificationKeys.getObject();authorityClients.getObject();publishers.getObject();
        },kafkaAdmin);
    }
    public static void bind(ApprovalWorkflowQuorumSlaRuntime runtime,SystemSlaProducerSource source) { runtime.bindProducer(source::authority); }
}
