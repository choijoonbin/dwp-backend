package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferAuthorityPorts.*;

@Configuration(proxyBeanMethods = false)
class ControlledTransferRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean(TrustedObjectMetadataAuthority.class)
    @ConditionalOnProperty(
            name = "dwp.hris.controlled-transfer.authority.enabled",
            havingValue = "false",
            matchIfMissing = true)
    TrustedObjectMetadataAuthority failClosedObjectMetadataAuthority() {
        return new FailClosedObjectMetadataAuthority();
    }

    @Bean
    @ConditionalOnMissingBean(SignedMalwareScanAuthority.class)
    @ConditionalOnProperty(
            name = "dwp.hris.controlled-transfer.authority.enabled",
            havingValue = "false",
            matchIfMissing = true)
    SignedMalwareScanAuthority failClosedMalwareScanAuthority() {
        return new FailClosedMalwareScanAuthority();
    }

    @Bean
    @ConditionalOnMissingBean(EgressPolicyAuthority.class)
    @ConditionalOnProperty(
            name = "dwp.hris.controlled-transfer.authority.enabled",
            havingValue = "false",
            matchIfMissing = true)
    EgressPolicyAuthority failClosedEgressPolicyAuthority() {
        return new FailClosedEgressPolicyAuthority();
    }
}
