package com.dwp.services.platform.media;

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
        prefix = "dwp.platform.assets",
        name = "storage",
        havingValue = "s3")
class S3TenantMediaStorageConfiguration {

    @Bean(destroyMethod = "close")
    S3Client tenantMediaS3Client(
            @Value("${dwp.platform.assets.s3.region}") String region,
            @Value("${dwp.platform.assets.s3.endpoint:}") String endpoint,
            @Value("${dwp.platform.assets.s3.path-style-access:false}")
                    boolean pathStyleAccess) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
