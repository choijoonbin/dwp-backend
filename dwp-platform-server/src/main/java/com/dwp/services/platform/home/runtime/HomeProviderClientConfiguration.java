package com.dwp.services.platform.home.runtime;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HomeProviderClientConfiguration {

    @Bean
    WidgetProviderPort approvalHomeWidgetProvider(
            RestClient.Builder builder,
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads,
            HomeRuntimeProperties runtime,
            @Value("${dwp.platform.home-runtime.providers.approval.url:http://localhost:8005}")
            String url,
            @Value("${dwp.platform.home-runtime.providers.approval.token:}") String token) {
        return client("approval", url, token, runtime, builder, circuitBreakers, bulkheads);
    }

    @Bean
    WidgetProviderPort meetingHomeWidgetProvider(
            RestClient.Builder builder,
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads,
            HomeRuntimeProperties runtime,
            @Value("${dwp.platform.home-runtime.providers.meeting.url:http://localhost:8009}")
            String url,
            @Value("${dwp.platform.home-runtime.providers.meeting.token:}") String token) {
        return client("meeting", url, token, runtime, builder, circuitBreakers, bulkheads);
    }

    @Bean
    WidgetProviderPort notificationHomeWidgetProvider(
            RestClient.Builder builder,
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads,
            HomeRuntimeProperties runtime,
            @Value("${dwp.platform.home-runtime.providers.notification.url:http://localhost:8008}")
            String url,
            @Value("${dwp.platform.home-runtime.providers.notification.token:}") String token) {
        return client("notification", url, token, runtime, builder, circuitBreakers, bulkheads);
    }

    @Bean
    WidgetProviderPort spaceHomeWidgetProvider(
            RestClient.Builder builder,
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads,
            HomeRuntimeProperties runtime,
            @Value("${dwp.platform.home-runtime.providers.space.url:http://localhost:8006}") String url,
            @Value("${dwp.platform.home-runtime.providers.space.token:}") String token) {
        return client("space", url, token, runtime, builder, circuitBreakers, bulkheads);
    }

    @Bean
    WidgetProviderPort messagingHomeWidgetProvider(
            RestClient.Builder builder,
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads,
            HomeRuntimeProperties runtime,
            @Value("${dwp.platform.home-runtime.providers.messaging.url:http://localhost:8007}")
            String url,
            @Value("${dwp.platform.home-runtime.providers.messaging.token:}") String token) {
        return client("messaging", url, token, runtime, builder, circuitBreakers, bulkheads);
    }

    @Bean
    WidgetProviderPort peopleHomeWidgetProvider(
            RestClient.Builder builder,
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads,
            HomeRuntimeProperties runtime,
            @Value("${dwp.platform.home-runtime.providers.people.url:http://localhost:8003}") String url,
            @Value("${dwp.platform.home-runtime.providers.people.token:}") String token) {
        return client("people", url, token, runtime, builder, circuitBreakers, bulkheads);
    }

    @Bean
    WidgetProviderPort dwaionHomeWidgetProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads,
            HomeRuntimeProperties runtime,
            @Value("${dwp.platform.home-runtime.providers.dwaion.url:http://localhost:8010}")
            String url,
            @Value("${dwp.platform.home-runtime.providers.dwaion.signing-secret:}")
            String signingSecret,
            @Value("${dwp.platform.home-runtime.providers.dwaion.key-id:platform-dwaion-home-v1}")
            String keyId) {
        if (signingSecret == null || signingSecret.isBlank()) {
            return new InactiveDwaionWidgetProvider();
        }
        DwaionHomeWorkloadAssertionSigner signer =
                new DwaionHomeWorkloadAssertionSigner(keyId, signingSecret, objectMapper);
        return new DwaionHomeWidgetProviderClient(
                url, runtime.providerTimeout(), builder, objectMapper, signer,
                circuitBreakers, bulkheads);
    }

    private WidgetProviderPort client(
            String key,
            String url,
            String token,
            HomeRuntimeProperties runtime,
            RestClient.Builder builder,
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads) {
        return new HttpWidgetProviderClient(
                key, url, token, runtime.commandsEnabled(), runtime.providerTimeout(), builder,
                circuitBreakers, bulkheads);
    }
}
