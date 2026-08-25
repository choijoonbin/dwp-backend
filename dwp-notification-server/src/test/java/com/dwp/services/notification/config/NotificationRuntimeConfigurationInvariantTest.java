package com.dwp.services.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class NotificationRuntimeConfigurationInvariantTest {

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
}
