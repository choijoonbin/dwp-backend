package com.dwp.core.autoconfig;

import com.dwp.core.filter.ApiHistoryServletFilter;
import com.dwp.core.filter.MdcCorrelationFilter;
import com.dwp.observability.api.ApiHistoryPublisher;
import com.dwp.observability.api.HttpApiHistoryPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

/** Registers common request tracing filters for servlet applications. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(FilterRegistrationBean.class)
public class CoreObservabilityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CoreObservabilityAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ApiHistoryPublisher.class)
    public ApiHistoryPublisher apiHistoryPublisher(
            ObjectMapper objectMapper,
            @Value("${dwp.observability.api-history.enabled:true}") boolean enabled,
            @Value("${dwp.observability.api-history.collector-url:}") String collectorUrl,
            @Value("${dwp.observability.api-history.ingest-token:}") String ingestToken,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${dwp.observability.api-history.queue-capacity:4096}") int queueCapacity,
            @Value("${dwp.observability.api-history.batch-size:100}") int batchSize,
            @Value("${dwp.observability.api-history.flush-interval:PT1S}") Duration flushInterval) {
        if (!enabled || collectorUrl.isBlank() || ingestToken.isBlank()) {
            log.info("DWP API history exporter is disabled or not configured");
            return ApiHistoryPublisher.NOOP;
        }
        return new HttpApiHistoryPublisher(
                URI.create(collectorUrl.trim()),
                ingestToken.trim(),
                serviceName,
                objectMapper,
                queueCapacity,
                batchSize,
                flushInterval);
    }

    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<MdcCorrelationFilter> mdcCorrelationFilter() {
        FilterRegistrationBean<MdcCorrelationFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new MdcCorrelationFilter());
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registrationBean;
    }

    @Bean
    @ConditionalOnMissingBean(name = "apiHistoryServletFilter")
    public FilterRegistrationBean<ApiHistoryServletFilter> apiHistoryServletFilter(
            ApiHistoryPublisher publisher,
            @Value("${dwp.observability.api-history.privacy-hash-secret:}") String privacyHashSecret,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${info.app.version:${spring.application.version:unknown}}") String serviceVersion,
            @Value("${dwp.observability.api-history.service-instance:${HOSTNAME:local}}") String serviceInstance,
            @Value("${dwp.observability.api-history.environment:${DWP_ENVIRONMENT:local}}") String environment) {
        FilterRegistrationBean<ApiHistoryServletFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new ApiHistoryServletFilter(
                publisher,
                privacyHashSecret,
                serviceName,
                serviceVersion,
                serviceInstance,
                environment));
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registrationBean;
    }
}
