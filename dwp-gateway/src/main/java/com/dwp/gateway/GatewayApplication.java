package com.dwp.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.gateway.config.CorsConfig;

@SpringBootApplication
@EnableConfigurationProperties(CorsConfig.class)
@ComponentScan(
    basePackages = {"com.dwp"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {GlobalExceptionHandler.class}
    )
)
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

