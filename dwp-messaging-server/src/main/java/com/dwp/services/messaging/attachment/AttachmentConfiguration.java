package com.dwp.services.messaging.attachment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AttachmentProperties.class)
class AttachmentConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "dwp.messaging.attachments", name = "storage",
            havingValue = "local", matchIfMissing = true)
    AttachmentStorage attachmentStorage(AttachmentProperties properties) {
        return new LocalAttachmentStorage(properties.localRoot());
    }

    @Bean
    AttachmentScanner attachmentScanner(AttachmentProperties properties) {
        return switch (properties.scanner().toLowerCase(java.util.Locale.ROOT)) {
            case "local" -> new LocalAttachmentScanner();
            case "clamav" -> new ClamAvAttachmentScanner(properties);
            default -> throw new IllegalStateException(
                    "Unsupported attachment scanner: " + properties.scanner());
        };
    }
}
