package com.dwp.core.autoconfig;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(DwpHttpClientProperties.class)
public class CoreHttpClientAutoConfiguration {

    @Bean
    RestClientCustomizer dwpRestClientTimeouts(DwpHttpClientProperties properties) {
        return builder -> {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(properties.connectTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
            factory.setReadTimeout(properties.readTimeout());
            builder.requestFactory(factory);
        };
    }
}
