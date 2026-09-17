package com.dwp.services.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class NotificationRuntimeConfigurationInvariantTest {

    @Test
    void meetingsProducerIdentityOwnershipAndEntitlementAreConfiguredTogether()
            throws IOException {
        String application = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(application)
                .contains("dwp-meeting-server=meetings")
                .contains("meetings=APP.MEETINGS:VIEW")
                .contains("dwp-messaging-server,dwp-meeting-server")
                .contains("urn:dwp:meetings=dwp-meeting-server");
    }

    @Test
    void workplaceProducerIdentityOwnershipEntitlementAndSourceAreConfiguredTogether()
            throws IOException {
        String application = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(application)
                .contains("dwp-platform-server=platform|workplace")
                .contains("workplace=APP.WORKPLACE:VIEW")
                .contains("urn:dwp:platform:workplace=dwp-platform-server");
    }

    @Test
    void mailProducerIdentityOwnershipEntitlementAndSourceAreConfiguredTogether()
            throws IOException {
        String application = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(application)
                .contains("dwp-platform-server=platform|workplace|mail")
                .contains("mail=APP.MAIL:VIEW")
                .contains("urn:dwp:platform:mail=dwp-platform-server");
    }

    @Test
    void longLivedStreamsDoNotRetainOpenEntityManagers() {
        ClassPathResource application = new ClassPathResource("application.yml");
        assertThat(application.exists()).isTrue();

        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(application);
        yaml.afterPropertiesSet();

        assertThat(yaml.getObject())
                .isNotNull()
                .containsEntry("spring.jpa.open-in-view", Boolean.FALSE);
    }

    @Test
    void recipientIdentityValidationHasBoundedResilienceAndExactViewBindings() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        yaml.afterPropertiesSet();

        assertThat(yaml.getObject())
                .isNotNull()
                .containsKeys(
                        "dwp.identity-sync.auth-url",
                        "dwp.identity-sync.token",
                        "dwp.notification.recipient-entitlements.app-view-bindings")
                .containsEntry(
                        "resilience4j.retry.instances.notificationIdentityDirectory.base-config",
                        "notificationIdentity");
    }
}
