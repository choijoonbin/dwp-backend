package com.dwp.services.synapsex.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gateway 등에서 path가 선행 슬래시 없이 전달될 때(예: synapse/demo/scenario-types) 컨트롤러 매칭 실패를 방지.
 * request URI가 "/"로 시작하지 않으면 "/"를 붙여 래핑하여 DispatcherServlet/ResourceHandler가 올바르게 매칭하도록 함.
 */
@Slf4j
@Component
@Order(-400)
public class RequestPathLeadingSlashFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null && !uri.isEmpty() && !uri.startsWith("/")) {
            if (log.isDebugEnabled()) {
                log.debug("Normalizing request path: '{}' -> '/{}'", uri, uri);
            }
            request = new LeadingSlashRequestWrapper(request, "/" + uri);
        }
        filterChain.doFilter(request, response);
    }

    private static final class LeadingSlashRequestWrapper extends HttpServletRequestWrapper {
        private final String normalizedRequestUri;

        LeadingSlashRequestWrapper(HttpServletRequest request, String normalizedRequestUri) {
            super(request);
            this.normalizedRequestUri = normalizedRequestUri;
        }

        @Override
        public String getRequestURI() {
            return normalizedRequestUri;
        }
    }
}
