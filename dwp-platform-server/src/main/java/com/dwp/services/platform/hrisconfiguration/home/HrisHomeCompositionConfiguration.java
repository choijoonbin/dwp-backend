package com.dwp.services.platform.hrisconfiguration.home;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Default-off activation seam. R4 supplies no production ports, route or controller; therefore an
 * attempted enablement without later controlled adapters fails startup closed.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.hris.home-composition.enabled",
        havingValue = "true",
        matchIfMissing = false)
class HrisHomeCompositionConfiguration {

    @Bean
    HrisHomeCompositionRuntimeControls hrisHomeCompositionRuntimeControls(
            @Value("${dwp.hris.home-composition.owner-timeout:PT0.5S}") Duration ownerTimeout,
            @Value("${dwp.hris.home-composition.max-owner-concurrency:4}")
            int maxOwnerConcurrency) {
        return new HrisHomeCompositionRuntimeControls(ownerTimeout, maxOwnerConcurrency);
    }

    @Bean(destroyMethod = "close")
    HrisHomeCompositionCoordinator hrisHomeCompositionCoordinator(
            HrisHomeCompositionPorts.OwnerMaterializationClient owners,
            HrisHomeCompositionRuntimeControls controls) {
        return new HrisHomeCompositionCoordinator(owners, controls);
    }

    @Bean
    HrisHomeCompositionService hrisHomeCompositionService(
            HrisHomeCompositionPorts.CandidateSource candidates,
            HrisHomeCompositionCoordinator coordinator,
            HrisHomeCompositionPorts.DeepLinkPolicy deepLinks) {
        return new HrisHomeCompositionService(
                candidates, coordinator, deepLinks, Clock.systemUTC());
    }
}
