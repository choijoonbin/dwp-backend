package com.dwp.services.messaging.retention;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingRetentionProperties.class)
class MessagingRetentionConfiguration {
}
