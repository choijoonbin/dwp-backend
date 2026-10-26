package com.dwp.services.approval.hrisbridge.separation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class HrisSeparationFeatureGateTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DisabledSlice.class)
            .withPropertyValues(
                    "dwp.approval.hrm013-bridge.enabled=false",
                    "dwp.approval.hrm013-bridge.relay-enabled=false");

    @Test
    void disabledFlagsInstantiateNoIngressRelayCredentialOrRotationRuntime() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(HrisSeparationApprovalController.class);
            assertThat(context).doesNotHaveBean(HrisSeparationDecisionRelay.class);
            assertThat(context).doesNotHaveBean(
                    HrisSeparationWorkloadCredentialProvider.class);
            assertThat(context).doesNotHaveBean(
                    HrisSeparationGrantRotationBarrier.class);
            assertThat(context).doesNotHaveBean(Hrm013RequesterIdentityClient.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({HrisSeparationApprovalController.class,
            HrisSeparationApprovalBridgeService.class,
            HrisSeparationDecisionRelay.class,
            HrisSeparationWorkloadCredentialProvider.class,
            HrisSeparationGrantRotationBarrier.class,
            Hrm013RequesterIdentityClient.class})
    static class DisabledSlice { }
}
