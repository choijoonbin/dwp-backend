package com.dwp.services.auth.controller.admin.monitoring;

import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Admin API 테스트용 Mock JWT 인증.
 * Principal을 Jwt로 설정하여 AdminGuardInterceptor가 통과하도록 한다.
 */
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockJwtSecurityContextFactory.class)
public @interface WithMockJwt {

    long userId() default 1L;

    long tenantId() default 1L;
}
