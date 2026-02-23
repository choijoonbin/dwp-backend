package com.dwp.services.synapsex.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * /ws/** 요청이 MVC RequestMappingHandlerMapping에 매칭되지 않도록 하여
 * STOMP/SockJS HandlerMapping만 처리하도록 함.
 * (eventsource, xhr_streaming 등 SockJS transport 경로가 MVC에 매칭되면
 * LinkedHashMap + text/event-stream converter 오류 발생)
 * RequestMappingHandlerMapping은 LOWEST_PRECEDENCE로 두어 WebSocket HandlerMapping이
 * 먼저 시도되도록 함 (그렇지 않으면 /ws/ 에서 null 반환 후 ResourceHandler가 "No static resource" 발생).
 */
@Configuration
public class WebSocketMvcExclusionConfig {

    private static final String WS_PATH_PREFIX = "/ws/";

    @Bean
    public WebMvcRegistrations webMvcRegistrations() {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping() {
                    @Override
                    protected HandlerMethod getHandlerInternal(@NonNull HttpServletRequest request) throws Exception {
                        String path = request.getRequestURI();
                        if (path != null && path.startsWith(WS_PATH_PREFIX)) {
                            return null;
                        }
                        return super.getHandlerInternal(request);
                    }
                };
                mapping.setOrder(Ordered.LOWEST_PRECEDENCE);
                return mapping;
            }
        };
    }
}
