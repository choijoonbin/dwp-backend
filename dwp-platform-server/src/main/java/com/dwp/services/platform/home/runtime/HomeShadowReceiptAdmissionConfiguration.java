package com.dwp.services.platform.home.runtime;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class HomeShadowReceiptAdmissionConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(
            name = "dwp.platform.home-runtime.shadow-receipt-admission.redis-enabled",
            havingValue = "true")
    RedisClient homeShadowReceiptRedisClient(
            @Value("${dwp.platform.home-runtime.shadow-receipt-admission.redis.host:localhost}")
            String host,
            @Value("${dwp.platform.home-runtime.shadow-receipt-admission.redis.port:6379}")
            int port,
            @Value("${dwp.platform.home-runtime.shadow-receipt-admission.redis.password:}")
            String password,
            @Value("${dwp.platform.home-runtime.shadow-receipt-admission.redis.ssl-enabled:false}")
            boolean sslEnabled,
            @Value("${dwp.platform.home-runtime.shadow-receipt-admission.redis.timeout:PT0.5S}")
            Duration timeout) {
        if (host == null || host.isBlank() || port < 1 || port > 65_535
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(2)) > 0) {
            throw new IllegalArgumentException(
                    "Home shadow receipt Redis configuration is invalid");
        }
        RedisURI.Builder uriBuilder = RedisURI.Builder.redis(host.trim(), port)
                .withSsl(sslEnabled)
                .withTimeout(timeout);
        if (password != null && !password.isEmpty()) {
            uriBuilder.withPassword((CharSequence) password);
        }
        return RedisClient.create(uriBuilder.build());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            name = "dwp.platform.home-runtime.shadow-receipt-admission.redis-enabled",
            havingValue = "true")
    StatefulRedisConnection<String, String> homeShadowReceiptRedisConnection(
            RedisClient homeShadowReceiptRedisClient) {
        return homeShadowReceiptRedisClient.connect();
    }

    @Bean
    @ConditionalOnProperty(
            name = "dwp.platform.home-runtime.shadow-receipt-admission.redis-enabled",
            havingValue = "true")
    HomeShadowReceiptAdmissionGuard redisHomeShadowReceiptAdmissionGuard(
            StatefulRedisConnection<String, String> homeShadowReceiptRedisConnection,
            @Value("${dwp.platform.home-runtime.shadow-receipt-admission.privacy-hash-secret:}")
            String privacyHashSecret) {
        return new RedisHomeShadowReceiptAdmissionGuard(
                homeShadowReceiptRedisConnection.sync(), privacyHashSecret);
    }

    @Bean
    @ConditionalOnProperty(
            name = "dwp.platform.home-runtime.shadow-receipt-admission.redis-enabled",
            havingValue = "false",
            matchIfMissing = true)
    HomeShadowReceiptAdmissionGuard disabledHomeShadowReceiptAdmissionGuard() {
        return (tenantId, userId, decisionRevision, request) ->
                HomeShadowReceiptAdmissionGuard.Admission.DISABLED;
    }
}
