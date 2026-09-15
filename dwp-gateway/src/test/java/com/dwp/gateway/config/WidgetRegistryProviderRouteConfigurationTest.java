package com.dwp.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class WidgetRegistryProviderRouteConfigurationTest {
    @Test
    void routesProviderWidgetRegistryThroughTheProviderSecurityBoundary() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        Properties properties = loader.getObject();

        assertThat(properties).isNotNull();
        var directPlatformRoutes = properties.entrySet().stream()
                .filter(entry -> entry.getKey().toString().endsWith(".id"))
                .filter(entry -> "widget-registry-provider-admin".equals(entry.getValue()))
                .toList();
        assertThat(directPlatformRoutes).isEmpty();

        var providerPrefixes = properties.entrySet().stream()
                .filter(entry -> entry.getKey().toString().endsWith(".id"))
                .filter(entry -> "provider-server".equals(entry.getValue()))
                .map(entry -> entry.getKey().toString().replaceFirst("\\.id$", ""))
                .toList();
        assertThat(providerPrefixes).hasSize(1);
        assertThat(properties.getProperty(providerPrefixes.getFirst() + ".predicates[0]"))
                .isEqualTo("Path=/api/provider/**");
        assertThat(properties.getProperty(providerPrefixes.getFirst() + ".filters[0]"))
                .isEqualTo("StripPrefix=2");
    }
}
