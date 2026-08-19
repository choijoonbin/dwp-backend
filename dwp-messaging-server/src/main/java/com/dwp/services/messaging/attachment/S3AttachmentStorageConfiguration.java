package com.dwp.services.messaging.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

@Configuration
@ConditionalOnProperty(
        prefix = "dwp.messaging.attachments", name = "storage", havingValue = "s3")
class S3AttachmentStorageConfiguration {

    @Bean(destroyMethod = "close")
    S3Client messagingAttachmentS3Client(
            @Value("${dwp.messaging.attachments.s3.region}") String region,
            @Value("${dwp.messaging.attachments.s3.endpoint:}") String endpoint,
            @Value("${dwp.messaging.attachments.s3.path-style-access:false}") boolean pathStyle) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle).build());
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean
    AttachmentStorage s3AttachmentStorage(
            S3Client messagingAttachmentS3Client,
            @Value("${dwp.messaging.attachments.s3.bucket}") String bucket,
            @Value("${dwp.messaging.attachments.s3.prefix:dwp-messaging/quarantine}") String prefix,
            @Value("${dwp.messaging.attachments.s3.kms-key-id:}") String kmsKeyId) {
        return new S3AttachmentStorage(
                messagingAttachmentS3Client, bucket, prefix, kmsKeyId);
    }
}
