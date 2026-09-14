package com.dwp.services.approval.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import java.net.URI;
import java.time.*;

@Configuration
@ConditionalOnProperty(prefix="dwp.approval.attachments",name="enabled",havingValue="true")
public class ApprovalAttachmentConfiguration {
    @Bean(destroyMethod="close") S3Client approvalAttachmentS3Client(@Value("${dwp.approval.attachments.s3.region}") String region,
            @Value("${dwp.approval.attachments.s3.endpoint:}") String endpoint,@Value("${dwp.approval.attachments.s3.path-style:false}") boolean pathStyle){
        var builder=S3Client.builder().region(Region.of(region)).httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(config->config.apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(15)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if(endpoint!=null && !endpoint.isBlank()) {
            var uri=URI.create(endpoint);if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
                throw new IllegalArgumentException("An explicitly configured TLS S3 endpoint is required.");builder.endpointOverride(uri);
        }
        return builder.build();
    }
    @Bean ApprovalAttachmentStorage approvalAttachmentStorage(S3Client approvalAttachmentS3Client,@Value("${dwp.approval.attachments.s3.bucket}") String bucket,
            @Value("${dwp.approval.attachments.s3.prefix:dwp-approval/quarantine}") String prefix){return new ApprovalAttachmentS3Storage(approvalAttachmentS3Client,bucket,prefix);}
    @Bean ApprovalAttachmentScanner approvalAttachmentScanner(@Value("${dwp.approval.attachments.clamav.host}") String host,
            @Value("${dwp.approval.attachments.clamav.port:3310}") int port,@Value("${dwp.approval.attachments.clamav.timeout-ms:10000}") int timeout){
        return new ApprovalAttachmentClamAv(host,port,timeout,Duration.ofDays(2),Clock.systemUTC());
    }
    @Bean ApprovalAttachmentScanWorker approvalAttachmentScanWorker(ApprovalAttachmentScanJobs jobs,ApprovalAttachmentStorage storage,ApprovalAttachmentScanner scanner,ApprovalAttachmentPassiveContent parser){
        return new ApprovalAttachmentScanWorker(jobs,storage,scanner,parser);
    }
}
