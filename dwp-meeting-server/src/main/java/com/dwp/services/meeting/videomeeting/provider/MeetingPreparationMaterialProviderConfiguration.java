package com.dwp.services.meeting.videomeeting.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeetingPreparationMaterialHttpProperties.class)
public class MeetingPreparationMaterialProviderConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "dwp.meeting.preparation-material",
            name = "provider",
            havingValue = "http")
    MeetingPreparationMaterialProvider governedHttpMeetingPreparationMaterialProvider(
            MeetingPreparationMaterialHttpProperties properties,
            ObjectMapper mapper) {
        return new GovernedHttpMeetingPreparationMaterialProvider(
                properties, mapper, new MeetingWorkloadAssertionSigner(properties));
    }

    @Bean
    @ConditionalOnMissingBean(MeetingPreparationMaterialProvider.class)
    MeetingPreparationMaterialProvider disabledMeetingPreparationMaterialProvider() {
        return request -> {
            throw new IllegalStateException("Meeting preparation material provider is disabled.");
        };
    }
}
