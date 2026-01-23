package com.dwp.core.config;

import com.dwp.core.constant.HeaderConstants;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FeignHeaderInterceptor 테스트
 * 
 * 목적:
 * - 표준 헤더가 누락 없이 downstream으로 전파되는지 검증
 * - 특히 X-Agent-ID, X-DWP-Caller-Type 포함 여부가 핵심 assertion
 * - 비동기 호출 시 안전하게 처리되는지 검증
 */
class FeignHeaderInterceptorTest {
    
    private FeignHeaderInterceptor interceptor;
    
    @Mock
    private HttpServletRequest request;
    
    @Mock
    private RequestTemplate requestTemplate;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        interceptor = new FeignHeaderInterceptor();
    }
    
    @Test
    void shouldPropagateAllRequiredHeaders() {
        // Given: 모든 표준 헤더가 존재하는 요청
        when(request.getHeader(HeaderConstants.AUTHORIZATION)).thenReturn("Bearer test-token");
        when(request.getHeader(HeaderConstants.X_TENANT_ID)).thenReturn("1");
        when(request.getHeader(HeaderConstants.X_USER_ID)).thenReturn("100");
        when(request.getHeader(HeaderConstants.X_AGENT_ID)).thenReturn("agent-123");
        when(request.getHeader(HeaderConstants.X_DWP_SOURCE)).thenReturn("AURA");
        when(request.getHeader(HeaderConstants.X_DWP_CALLER_TYPE)).thenReturn("AGENT");
        
        ServletRequestAttributes attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        
        // When: Interceptor 적용
        interceptor.apply(requestTemplate);
        
        // Then: 모든 헤더가 전파되었는지 검증
        verify(requestTemplate).header(HeaderConstants.AUTHORIZATION, "Bearer test-token");
        verify(requestTemplate).header(HeaderConstants.X_TENANT_ID, "1");
        verify(requestTemplate).header(HeaderConstants.X_USER_ID, "100");
        verify(requestTemplate).header(HeaderConstants.X_AGENT_ID, "agent-123");
        verify(requestTemplate).header(HeaderConstants.X_DWP_SOURCE, "AURA");
        verify(requestTemplate).header(HeaderConstants.X_DWP_CALLER_TYPE, "AGENT");
        
        // Cleanup
        RequestContextHolder.resetRequestAttributes();
    }
    
    @Test
    void shouldPropagateXAgentIdHeader() {
        // Given: X-Agent-ID 헤더가 존재하는 요청
        when(request.getHeader(HeaderConstants.X_AGENT_ID)).thenReturn("agent-456");
        when(request.getHeader(HeaderConstants.AUTHORIZATION)).thenReturn("Bearer token");
        when(request.getHeader(HeaderConstants.X_TENANT_ID)).thenReturn("1");
        
        ServletRequestAttributes attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        
        // When: Interceptor 적용
        interceptor.apply(requestTemplate);
        
        // Then: X-Agent-ID가 전파되었는지 검증 (핵심 assertion)
        verify(requestTemplate).header(HeaderConstants.X_AGENT_ID, "agent-456");
        
        // Cleanup
        RequestContextHolder.resetRequestAttributes();
    }
    
    @Test
    void shouldPropagateXDwpCallerTypeHeader() {
        // Given: X-DWP-Caller-Type 헤더가 존재하는 요청
        when(request.getHeader(HeaderConstants.X_DWP_CALLER_TYPE)).thenReturn("AGENT");
        when(request.getHeader(HeaderConstants.AUTHORIZATION)).thenReturn("Bearer token");
        when(request.getHeader(HeaderConstants.X_TENANT_ID)).thenReturn("1");
        
        ServletRequestAttributes attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        
        // When: Interceptor 적용
        interceptor.apply(requestTemplate);
        
        // Then: X-DWP-Caller-Type이 전파되었는지 검증 (핵심 assertion)
        verify(requestTemplate).header(HeaderConstants.X_DWP_CALLER_TYPE, "AGENT");
        
        // Cleanup
        RequestContextHolder.resetRequestAttributes();
    }
    
    @Test
    void shouldNotPropagateNullHeaders() {
        // Given: 일부 헤더만 존재하는 요청
        when(request.getHeader(HeaderConstants.AUTHORIZATION)).thenReturn("Bearer token");
        when(request.getHeader(HeaderConstants.X_TENANT_ID)).thenReturn("1");
        // X-Agent-ID, X-DWP-Source 등은 null
        when(request.getHeader(HeaderConstants.X_AGENT_ID)).thenReturn(null);
        when(request.getHeader(HeaderConstants.X_DWP_SOURCE)).thenReturn(null);
        when(request.getHeader(HeaderConstants.X_DWP_CALLER_TYPE)).thenReturn(null);
        
        ServletRequestAttributes attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        
        // When: Interceptor 적용
        interceptor.apply(requestTemplate);
        
        // Then: null 헤더는 전파되지 않음
        verify(requestTemplate).header(HeaderConstants.AUTHORIZATION, "Bearer token");
        verify(requestTemplate).header(HeaderConstants.X_TENANT_ID, "1");
        verify(requestTemplate, never()).header(eq(HeaderConstants.X_AGENT_ID), anyString());
        verify(requestTemplate, never()).header(eq(HeaderConstants.X_DWP_SOURCE), anyString());
        verify(requestTemplate, never()).header(eq(HeaderConstants.X_DWP_CALLER_TYPE), anyString());
        
        // Cleanup
        RequestContextHolder.resetRequestAttributes();
    }
    
    @Test
    void shouldNotPropagateEmptyHeaders() {
        // Given: 빈 문자열 헤더
        when(request.getHeader(HeaderConstants.AUTHORIZATION)).thenReturn("Bearer token");
        when(request.getHeader(HeaderConstants.X_TENANT_ID)).thenReturn("");  // 빈 문자열
        
        ServletRequestAttributes attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        
        // When: Interceptor 적용
        interceptor.apply(requestTemplate);
        
        // Then: 빈 문자열 헤더는 전파되지 않음
        verify(requestTemplate).header(HeaderConstants.AUTHORIZATION, "Bearer token");
        verify(requestTemplate, never()).header(eq(HeaderConstants.X_TENANT_ID), anyString());
        
        // Cleanup
        RequestContextHolder.resetRequestAttributes();
    }
    
    @Test
    void shouldHandleNoRequestContext() {
        // Given: RequestContext가 없는 상황 (비동기 호출, 스케줄러 등)
        RequestContextHolder.resetRequestAttributes();
        
        // When: Interceptor 적용
        assertDoesNotThrow(() -> interceptor.apply(requestTemplate));
        
        // Then: 예외 발생 없이 안전하게 처리
        verify(requestTemplate, never()).header(anyString(), anyString());
    }
}
