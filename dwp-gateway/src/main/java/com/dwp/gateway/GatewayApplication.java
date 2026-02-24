package com.dwp.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.gateway.config.CorsConfig;

@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class
})
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
        // WebSocket 업그레이드(101)는 Reactor Netty 필수.
        // Tomcat이 classpath에 있어도(IDE run 등) REACTIVE 모드를 강제해 Netty만 기동.
        new SpringApplicationBuilder(GatewayApplication.class)
                .web(WebApplicationType.REACTIVE)
                .run(args);
    }
}
