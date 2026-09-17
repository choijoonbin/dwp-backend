package com.dwp.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalAttachmentCorsConfigurationTest {

    @Test
    void exposesAttachmentIntegrityHeadersToBrowserClients() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        Properties properties = loader.getObject();

        assertThat(properties).isNotNull();
        Set<String> exposedHeaders = properties.stringPropertyNames().stream()
                .filter(key -> key.contains("globalcors") && key.contains("exposed-headers"))
                .map(properties::getProperty)
                .collect(Collectors.toSet());

        assertThat(exposedHeaders)
                .contains("X-Correlation-ID", "X-Content-SHA256", "X-Content-Type-Options");
    }

    @Test
    void exposesHomeRuntimeTrustHeadersToBrowserClients() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        Properties properties = loader.getObject();

        assertThat(properties).isNotNull();
        Set<String> exposedHeaders = properties.stringPropertyNames().stream()
                .filter(key -> key.contains("globalcors") && key.contains("exposed-headers"))
                .map(properties::getProperty)
                .collect(Collectors.toSet());

        assertThat(exposedHeaders).contains(
                "ETag",
                "Vary",
                "X-DWP-Decision-Revision",
                "X-DWP-Home-Runtime-Mode",
                "X-DWP-Home-Runtime-State",
                "X-DWP-Home-Rollout-Ring",
                "X-DWP-Home-Rollout-Revision",
                "X-DWP-Home-Commands-Enabled",
                "X-DWP-Widget-Registry-Authoritative");
    }
}
