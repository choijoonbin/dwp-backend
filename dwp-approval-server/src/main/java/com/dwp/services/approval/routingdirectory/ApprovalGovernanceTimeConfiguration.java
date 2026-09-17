package com.dwp.services.approval.routingdirectory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ApprovalGovernanceTimeConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock approvalGovernanceClock() {
        return Clock.systemUTC();
    }
}
