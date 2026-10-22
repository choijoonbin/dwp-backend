package com.dwp.services.payroll.statutory.pay004;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAY_RETIREMENT_WORKBENCH.enabled",
        havingValue = "true", matchIfMissing = false)
class Pay004UnavailableProviders {
    @Bean
    @ConditionalOnMissingBean(Pay004Ports.ProjectionDecisionPort.class)
    Pay004Ports.ProjectionDecisionPort unavailablePay004ProjectionDecisions() {
        return (actor, variant) -> Map.of();
    }
}
