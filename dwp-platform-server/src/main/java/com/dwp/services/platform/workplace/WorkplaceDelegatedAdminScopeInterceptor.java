package com.dwp.services.platform.workplace;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
class WorkplaceDelegatedAdminScopeInterceptor implements HandlerInterceptor {

    private final WorkplaceDelegatedAdminScopeGuard guard;

    WorkplaceDelegatedAdminScopeInterceptor(WorkplaceDelegatedAdminScopeGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        guard.authorize(request);
        return true;
    }
}
