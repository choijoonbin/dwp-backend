package com.dwp.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingPublicRouteIsolationConfigurationTest {

    @Test
    void routesOnlyThePublicMeetingV1Namespace() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        Properties properties = loader.getObject();

        assertThat(properties).isNotNull();
        var routePrefixes = properties.entrySet().stream()
                .filter(entry -> entry.getKey().toString().endsWith(".id"))
                .filter(entry -> "meeting-server".equals(entry.getValue()))
                .map(entry -> entry.getKey().toString().substring(
                        0, entry.getKey().toString().length() - ".id".length()))
                .toList();
        assertThat(routePrefixes).hasSize(1);
        String routePrefix = routePrefixes.get(0);
        String predicate = properties.getProperty(routePrefix + ".predicates[0]");

        assertThat(predicate).isEqualTo("Path=/api/meetings/v1/**");
        PathPattern publicRoute = PathPatternParser.defaultInstance.parse(
                predicate.substring("Path=".length()));
        assertThat(matches(publicRoute, "/api/meetings/v1/home")).isTrue();
        assertThat(matches(publicRoute,
                "/api/meetings/v1/meetings/00000000-0000-0000-0000-000000000001"))
                .isTrue();
        assertThat(matches(publicRoute,
                "/api/meetings/internal/v1/meetings/00000000-0000-0000-0000-000000000001"
                        + "/artifacts/recording/finalize"))
                .isFalse();
        assertThat(matches(publicRoute, "/api/meetings/v1-internal/probe")).isFalse();
    }

    private boolean matches(PathPattern pattern, String path) {
        return pattern.matches(PathContainer.parsePath(path));
    }
}
