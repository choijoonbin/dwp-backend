package com.dwp.services.payroll.statutory.pay003;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_STATUTORY_INSURANCE.enabled",
        havingValue = "true",
        matchIfMissing = false)
class Pay003UnavailableProviders {

    @Bean
    @ConditionalOnMissingBean(Pay003Ports.SignatureVerificationPort.class)
    Pay003Ports.SignatureVerificationPort unavailablePay003SignatureVerifier() {
        return ignored -> Pay003Ports.Verification.UNAVAILABLE;
    }

    @Bean
    @ConditionalOnMissingBean(Pay003Ports.StatutoryApprovalPort.class)
    Pay003Ports.StatutoryApprovalPort unavailablePay003ApprovalSource() {
        return (actor, snapshot, receiptId) -> new Pay003Ports.ApprovalEvidence(
                Pay003Ports.Verification.UNAVAILABLE, receiptId, null,
                actor.subject().tenantId(), actor.scope().legalEntityId(),
                actor.scope().payrollGroupId(), null, null);
    }

    @Bean
    @ConditionalOnMissingBean(Pay003Ports.CountryRuleProviderPort.class)
    Pay003Ports.CountryRuleProviderPort unavailablePay003CountryRuleProvider() {
        return (snapshot, command) -> Pay003Ports.Verification.UNAVAILABLE;
    }

    @Bean
    @ConditionalOnMissingBean(Pay003Ports.ProjectionDecisionPort.class)
    Pay003Ports.ProjectionDecisionPort unavailablePay003ProjectionDecisions() {
        return (actor, variant) -> Map.of();
    }

    @Bean
    @ConditionalOnMissingBean(Pay003Ports.ActivationDecisionPort.class)
    Pay003Ports.ActivationDecisionPort unavailablePay003ActivationDecision() {
        return (actor, snapshot, preliminary) -> false;
    }
}
