package com.dwp.services.platform.widgetregistry.internal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WidgetRegistryActivationInterlockTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withUserConfiguration(WidgetRegistryScanConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = WidgetRegistryInternalSecurityFilter.class)
    static class WidgetRegistryScanConfiguration {
    }

    @Test
    void missingConfigurationRegistersTheReceiverAndRemainsDenied() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(WidgetRegistryActivationProperties.class);
            assertThat(context).hasSingleBean(WidgetRegistryActivationInterlock.class);
            assertThat(context).hasSingleBean(WidgetRegistryInternalSecurityFilter.class);
            assertThat(context.getBean(WidgetRegistryActivationProperties.class).enabled()).isFalse();
            assertThat(context.getBean(WidgetRegistryActivationInterlock.class).permitsRequest())
                    .isFalse();
        });
    }

    @Test
    void explicitFalseAndTestProfileDoNotCreateAnAllowPath() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=test",
                        "dwp.platform.widget-registry-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(WidgetRegistryInternalSecurityFilter.class);
                    assertThat(context.getBean(WidgetRegistryActivationInterlock.class)
                                    .permitsRequest())
                            .isFalse();
                });
    }

    @Test
    void trueIsRejectedDuringInitializationAndNeverPermitsARequest() {
        WidgetRegistryActivationInterlock interlock = new WidgetRegistryActivationInterlock(
                new WidgetRegistryActivationProperties(true));

        assertThat(interlock.permitsRequest()).isFalse();
        assertThatThrownBy(interlock::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Widget Registry activation is blocked");

        contextRunner
                .withPropertyValues("dwp.platform.widget-registry-enabled=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull()
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasStackTraceContaining("Widget Registry activation is blocked"));
    }

    @Test
    void malformedConfigurationFailsContextCreation() {
        contextRunner
                .withPropertyValues("dwp.platform.widget-registry-enabled=not-a-boolean")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Test
    void arbitraryTrustAdapterCardinalityCannotAffectTheDecision() {
        contextRunner
                .withBean(
                        WidgetRegistryTrustPorts.ServiceTokenVerifier.class,
                        () -> compact -> null)
                .withBean(
                        WidgetRegistryTrustPorts.ProviderAssertionVerifier.class,
                        () -> (compact, kind) -> null)
                .withBean(
                        WidgetRegistryTrustPorts.AssertionReplayStore.class,
                        () -> (key, retainUntil) -> WidgetRegistryTrustPorts.ReplayDecision.ACCEPTED)
                .run(context -> {
                    assertThat(context).hasSingleBean(WidgetRegistryInternalSecurityFilter.class);
                    assertThat(context.getBean(WidgetRegistryActivationInterlock.class)
                                    .permitsRequest())
                            .isFalse();
                });

        contextRunner
                .withBean(
                        "firstWidgetServiceVerifier",
                        WidgetRegistryTrustPorts.ServiceTokenVerifier.class,
                        () -> compact -> null)
                .withBean(
                        "secondWidgetServiceVerifier",
                        WidgetRegistryTrustPorts.ServiceTokenVerifier.class,
                        () -> compact -> null)
                .run(context -> {
                    assertThat(context).hasSingleBean(WidgetRegistryInternalSecurityFilter.class);
                    assertThat(context.getBean(WidgetRegistryActivationInterlock.class)
                                    .permitsRequest())
                            .isFalse();
                });
    }

    @Test
    void springWiredReceiverStopsBeforeEveryDownstreamCapability() {
        AtomicInteger serviceVerificationCalls = new AtomicInteger();
        AtomicInteger assertionVerificationCalls = new AtomicInteger();
        AtomicInteger replayCalls = new AtomicInteger();
        AtomicInteger bodyReads = new AtomicInteger();
        AtomicInteger downstreamCalls = new AtomicInteger();

        contextRunner
                .withBean(
                        WidgetRegistryTrustPorts.ServiceTokenVerifier.class,
                        () -> compact -> {
                            serviceVerificationCalls.incrementAndGet();
                            return null;
                        })
                .withBean(
                        WidgetRegistryTrustPorts.ProviderAssertionVerifier.class,
                        () -> (compact, kind) -> {
                            assertionVerificationCalls.incrementAndGet();
                            return null;
                        })
                .withBean(
                        WidgetRegistryTrustPorts.AssertionReplayStore.class,
                        () -> (key, retainUntil) -> {
                            replayCalls.incrementAndGet();
                            return WidgetRegistryTrustPorts.ReplayDecision.ACCEPTED;
                        })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WidgetRegistryInternalSecurityFilter filter =
                            context.getBean(WidgetRegistryInternalSecurityFilter.class);
                    MockHttpServletRequest base = new MockHttpServletRequest(
                            "POST", "/internal/provider/v1/widget-registry/commands");
                    base.setSecure(true);
                    base.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    base.setContent("{}".getBytes(StandardCharsets.UTF_8));
                    base.addHeader("Authorization", "Bearer service.payload.signature");
                    base.addHeader(
                            "X-DWP-Widget-Assertion", "assertion.payload.signature");
                    HttpServletRequestWrapper request = new HttpServletRequestWrapper(base) {
                        @Override
                        public jakarta.servlet.ServletInputStream getInputStream() {
                            bodyReads.incrementAndGet();
                            return base.getInputStream();
                        }

                        @Override
                        public java.io.BufferedReader getReader() throws java.io.IOException {
                            bodyReads.incrementAndGet();
                            return base.getReader();
                        }
                    };
                    MockHttpServletResponse response = new MockHttpServletResponse();

                    filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                            downstreamCalls.incrementAndGet());

                    assertThat(response.getStatus()).isEqualTo(503);
                });

        assertThat(serviceVerificationCalls).hasValue(0);
        assertThat(assertionVerificationCalls).hasValue(0);
        assertThat(replayCalls).hasValue(0);
        assertThat(bodyReads).hasValue(0);
        assertThat(downstreamCalls).hasValue(0);
    }
}
