package com.dwp.services.provider.rollout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class FeatureRolloutDecisionPublisherConfiguration {

    @Bean
    @ConditionalOnMissingBean(FeatureRolloutDecisionEventPublisher.class)
    FeatureRolloutDecisionEventPublisher featureRolloutDecisionEventPublisher() {
        return FeatureRolloutDecisionEventPublisher.NOOP;
    }
}
