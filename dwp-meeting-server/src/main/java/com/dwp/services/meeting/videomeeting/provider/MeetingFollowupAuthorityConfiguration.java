package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.videomeeting.domain.MeetingFollowupCurrentAuthority;
import com.dwp.services.meeting.videomeeting.domain.UnavailableMeetingFollowupCurrentAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeetingFollowupAuthorityProperties.class)
public class MeetingFollowupAuthorityConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "dwp.meeting.followup-authority",
            name = "provider",
            havingValue = "auth-product-surface")
    MeetingFollowupCurrentAuthority governedProductSurfaceMeetingFollowupCurrentAuthority(
            MeetingFollowupAuthorityProperties properties,
            ObjectMapper mapper) {
        return new GovernedProductSurfaceMeetingFollowupCurrentAuthority(properties, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(MeetingFollowupCurrentAuthority.class)
    MeetingFollowupCurrentAuthority unavailableMeetingFollowupCurrentAuthority() {
        return new UnavailableMeetingFollowupCurrentAuthority();
    }
}
