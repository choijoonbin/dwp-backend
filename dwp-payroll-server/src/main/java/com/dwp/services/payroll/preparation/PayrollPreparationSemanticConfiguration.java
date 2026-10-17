package com.dwp.services.payroll.preparation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_RUN_PREPARE.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PayrollPreparationSemanticConfiguration {

    @Bean
    @ConditionalOnMissingBean(PayrollPreparationSemanticSnapshotPort.class)
    PayrollPreparationSemanticSnapshotPort payrollPreparationSemanticSnapshotPort() {
        return (lines, formulas) ->
                PayrollPreparationSemanticSnapshotPort.Resolution.unavailable();
    }

    @Bean
    @ConditionalOnMissingBean(PayrollPreparationPopulationSnapshotPort.class)
    PayrollPreparationPopulationSnapshotPort payrollPreparationPopulationSnapshotPort() {
        return new PayrollPreparationPopulationSnapshotPort() {
            @Override
            public Review review(
                    PayrollPreparationAuthority.Actor actor,
                    PayrollPreparationAuthority.PopulationTuple population,
                    java.time.Instant asOf) {
                return Review.unavailable();
            }

            @Override
            public Materialization materialize(
                    PayrollPreparationAuthority.Actor actor,
                    PayrollPreparationAuthority.PopulationTuple population,
                    java.util.UUID payPeriodId,
                    java.time.Instant asOf,
                    String reviewedDigest) {
                return Materialization.unavailable();
            }
        };
    }
}
