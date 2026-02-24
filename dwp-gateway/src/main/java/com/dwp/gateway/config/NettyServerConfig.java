package com.dwp.gateway.config;

import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gateway 서버 팩토리 — Reactor Netty 강제.
 *
 * Spring Cloud Gateway의 WebSocket 업그레이드(101)는 Reactor Netty 전용.
 * Tomcat이 클래스패스에 있을 때(IDE run 등) TomcatReactiveWebServerFactory가
 * 자동 구성되지 않도록, Netty 팩토리를 명시적 @Bean으로 등록한다.
 * ReactiveWebServerFactory @Bean이 존재하면 자동 구성의 @ConditionalOnMissingBean이
 * 충족되지 않아 Tomcat 팩토리가 등록되지 않는다.
 */
@Configuration
public class NettyServerConfig {

    @Bean
    public ReactiveWebServerFactory reactiveWebServerFactory() {
        return new NettyReactiveWebServerFactory();
    }
}
