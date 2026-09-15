package com.dwp.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class WidgetRegistryProviderRouteConfigurationTest {
    @Test
    void routesOnlyProviderControlPlaneApisToPlatformWithATrustedMarker() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        Properties properties = loader.getObject();

        assertThat(properties).isNotNull();
        var prefixes = properties.entrySet().stream()
                .filter(entry -> entry.getKey().toString().endsWith(".id"))
                .filter(entry -> "widget-registry-provider-admin".equals(entry.getValue()))
                .map(entry -> entry.getKey().toString().replaceFirst("\\.id$", ""))
                .toList();
        assertThat(prefixes).hasSize(1);
        String prefix = prefixes.get(0);

        assertThat(properties.getProperty(prefix + ".predicates[0]")).isEqualTo(
                "Path=/api/provider/v1/admin/widget-definitions/**,"
                        + "/api/provider/v1/admin/widget-definition-versions/**,"
                        + "/api/provider/v1/admin/widget-runtime-controls/**,"
                        + "/api/provider/v1/admin/widget-registry/**");
        assertThat(properties.entrySet().stream()
                .filter(entry -> entry.getKey().toString().startsWith(prefix + ".filters"))
                .map(entry -> entry.getValue().toString()))
                .contains("StripPrefix=2",
                        "SetRequestHeader=X-DWP-Control-Plane, WIDGET_REGISTRY_PROVIDER")
                .noneMatch(value -> value.contains("widget-catalog")
                        || value.contains("widget-policies")
                        || value.contains("widget-data"));
    }
}
