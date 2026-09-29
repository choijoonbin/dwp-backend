package com.dwp.services.people.hris.people;

import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQueryPort;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQueryPorts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Publishes the guarded HRM owner port only with the PER Wave 1 runtime latch enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.hris.performance.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
class HrisWorkforceSnapshotOwnerConfiguration {

    @Bean
    WorkforceSnapshotQueryPort workforceSnapshotQueryPort(
            HrisWorkforceSnapshotAuthorityVerifier verifier,
            HrisWorkforceSnapshotProvider provider) {
        return WorkforceSnapshotQueryPorts.guarded(verifier, provider);
    }
}
