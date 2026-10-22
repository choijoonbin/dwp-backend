package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.security.Hrm012ReceiptFirstStepUpClient;
import com.dwp.services.people.security.Hrm012ReceiptFirstStepUpDtos;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** No production object, mapper, effect, quarantine or pre-receipt Auth binding in HRM-012. */
@Configuration(proxyBeanMethods = false)
public class Hrm012FailClosedWorkforceIngestionAdapters {

    @Bean
    @ConditionalOnMissingBean(Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort.class)
    Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort trustedSnapshot() {
        return request -> {
            throw Hrm012Problem.unavailable(
                    "A complete trusted pre-receipt Auth snapshot is not bound.");
        };
    }

    @Bean
    @ConditionalOnMissingBean(Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort.class)
    Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort postReceiptAuthority(
            Hrm012ReceiptFirstStepUpClient client) {
        return request -> {
            client.verify(new Hrm012ReceiptFirstStepUpDtos.VerifyRequest(
                    request.tenantId(), Long.toString(request.numericUserId()),
                    request.routeContractKey(), request.commandMethod(),
                    request.publicCommandPath(), request.serviceCommandPath(),
                    request.objectRef(), request.ifMatch(), request.purpose(),
                    request.opaqueStepUpChallengeRef(), request.idempotencyKey(),
                    request.contextKey(), request.scopeRef(), request.payloadSha256(),
                    request.postReceiptDecisionRevision(),
                    request.receiptPublicId().toString(),
                    request.canonicalRequestHash(), request.correlationId().toString()));
            // The current central response attests only decision plus three revisions.
            // It cannot be expanded into the complete verdict by echoing this request.
            throw Hrm012Problem.unavailable(
                    "A complete trusted post-receipt Auth verdict is not bound.");
        };
    }

    @Bean
    @ConditionalOnMissingBean(Hrm012WorkforceIngestionPorts.OwnerMetadataPort.class)
    Hrm012WorkforceIngestionPorts.OwnerMetadataPort ownerMetadata() {
        return request -> {
            throw Hrm012Problem.unavailable("The workforce owner metadata provider is not bound.");
        };
    }

    @Bean
    @ConditionalOnMissingBean(Hrm012WorkforceIngestionPorts.ArtifactPort.class)
    Hrm012WorkforceIngestionPorts.ArtifactPort artifact() {
        return request -> {
            throw Hrm012Problem.unavailable("The workforce object provider is not bound.");
        };
    }

    @Bean
    @ConditionalOnMissingBean(Hrm012WorkforceIngestionPorts.MappingPort.class)
    Hrm012WorkforceIngestionPorts.MappingPort mapping() {
        return request -> {
            throw Hrm012Problem.unavailable("The published workforce mapping is not bound.");
        };
    }

    @Bean
    @ConditionalOnMissingBean(Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort.class)
    Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort effects() {
        return new Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort() {
            @Override
            public Hrm012WorkforceIngestionPorts.EffectResult apply(
                    Hrm012WorkforceIngestionPorts.EffectRequest request) {
                throw Hrm012Problem.unavailable(
                        "A transaction-participating workforce effect provider is not bound.");
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(Hrm012WorkforceIngestionPorts.QuarantineProtectorPort.class)
    Hrm012WorkforceIngestionPorts.QuarantineProtectorPort quarantine() {
        return request -> {
            throw Hrm012Problem.unavailable("The workforce quarantine protector is not bound.");
        };
    }
}
