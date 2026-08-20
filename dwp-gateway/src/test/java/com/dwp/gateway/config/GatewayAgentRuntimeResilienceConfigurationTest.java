package com.dwp.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayAgentRuntimeResilienceConfigurationTest {

    @Test
    void givesTheAgentModelRouteAnExplicitLongRunningBudget() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        Properties properties = loader.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty(
                "resilience4j.circuitbreaker.instances.agentRuntime.base-config"))
                .isEqualTo("default");
        assertThat(properties.getProperty(
                "resilience4j.circuitbreaker.instances.agentRuntime.slow-call-duration-threshold"))
                .isEqualTo("${DWP_AGENT_SLOW_CALL_THRESHOLD:30s}");
        assertThat(properties.getProperty(
                "resilience4j.timelimiter.instances.agentRuntime.timeout-duration"))
                .isEqualTo("${DWP_AGENT_GATEWAY_TIMEOUT:45s}");
    }
}
