package com.dwp.services.payroll.home;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static com.dwp.services.payroll.home.PayrollHomePorts.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PayrollHomeProducerProperties.class)
@ConditionalOnProperty(
        name = "dwp.hris.payroll.home-producer.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PayrollHomeProducerConfiguration {

    @Bean
    PayrollHomePayloadCodec payrollHomePayloadCodec(
            PayrollHomeProducerProperties properties,
            ObjectMapper mapper) {
        properties.validateForActivation();
        return new PayrollHomePayloadCodec(properties.getPayloadReferenceSecret(), mapper);
    }

    @Bean
    PayrollHomeSigningKeySource payrollHomeSigningKeySource(
            PayrollHomeProducerProperties properties) {
        return new PayrollHomeTrustProfile(properties);
    }

    @Bean
    PayrollHomeContributionProjector payrollHomeContributionProjector(
            PayrollHomePayloadCodec codec,
            PayrollHomeProducerProperties properties) {
        return new PayrollHomeContributionProjector(
                codec, properties.getFreshFor(), properties.getStaleAfter());
    }

    @Bean
    PayrollHomeAdmission payrollHomeAdmission(
            ObjectMapper mapper,
            ObjectProvider<Clock> clocks,
            PayrollHomeSigningKeySource keys,
            PayrollHomeCurrentAuthority authority,
            PayrollHomeReplayLedger replay) {
        return new PayrollHomeAdmission(
                mapper, clocks.getIfAvailable(Clock::systemUTC), keys, authority, replay);
    }

    @Bean
    PayrollHomeEventPublisher payrollHomeEventPublisher(
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper mapper,
            PayrollHomeProducerProperties properties) {
        return new PayrollHomeEventPublisher(
                recorder, contracts, mapper, properties.isEventPublicationEnabled());
    }

    @Bean
    PayrollHomeContributionService payrollHomeContributionService(
            PayrollHomeAdmission admission,
            PayrollHomeProjectionSource source,
            PayrollHomeContributionProjector projector,
            PayrollHomePayloadCodec codec,
            PayrollHomeEventPublisher events,
            ObjectProvider<Clock> clocks) {
        return new PayrollHomeContributionService(
                admission, source, projector, codec, events,
                clocks.getIfAvailable(Clock::systemUTC));
    }

}
