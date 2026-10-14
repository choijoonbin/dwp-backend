package com.dwp.services.payroll.calculation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Dark-feature wiring for the authoritative payroll semantic boundary. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PayrollCalculationSemanticConfiguration {

    @Bean
    @ConditionalOnMissingBean(PayrollCalculationSemanticMappingPort.class)
    PayrollCalculationSemanticMappingPort payrollCalculationSemanticMappingPort() {
        return new UnavailablePayrollCalculationSemanticMappingPort();
    }
}
