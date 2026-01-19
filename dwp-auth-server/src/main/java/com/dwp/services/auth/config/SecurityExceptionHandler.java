package com.dwp.services.auth.config;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security 예외 처리 핸들러
 * 
 * 401 (AuthenticationException) 및 403 (AccessDeniedException)을
 * ApiResponse 형식으로 변환하여 반환합니다.
 */
@Slf4j
@Component
public class SecurityExceptionHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    
    private final ObjectMapper objectMapper;
    
    public SecurityExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * 401 Unauthorized 처리
     * 
     * 인증되지 않은 요청 또는 잘못된 JWT 토큰에 대한 응답
     */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        
        log.warn("Authentication failed: path={}, error={}", 
                request.getRequestURI(), authException.getMessage());
        
        ErrorCode errorCode = determineErrorCode(authException);
        ApiResponse<Object> apiResponse = ApiResponse.error(errorCode, 
                authException.getMessage() != null ? authException.getMessage() : errorCode.getMessage());
        
        writeResponse(response, errorCode.getHttpStatus().value(), apiResponse);
    }
    
    /**
     * 403 Forbidden 처리
     * 
     * 인증은 되었으나 권한이 없는 요청에 대한 응답
     */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        
        log.warn("Access denied: path={}, error={}", 
                request.getRequestURI(), accessDeniedException.getMessage());
        
        ApiResponse<Object> apiResponse = ApiResponse.error(ErrorCode.FORBIDDEN, 
                accessDeniedException.getMessage() != null ? 
                        accessDeniedException.getMessage() : ErrorCode.FORBIDDEN.getMessage());
        
        writeResponse(response, ErrorCode.FORBIDDEN.getHttpStatus().value(), apiResponse);
    }
    
    /**
     * 예외 타입에 따라 적절한 ErrorCode 결정
     */
    private ErrorCode determineErrorCode(AuthenticationException e) {
        String message = e.getMessage();
        if (message != null) {
            if (message.contains("expired") || message.contains("만료")) {
                return ErrorCode.TOKEN_EXPIRED;
            }
            if (message.contains("invalid") || message.contains("유효하지")) {
                return ErrorCode.TOKEN_INVALID;
            }
        }
        return ErrorCode.UNAUTHORIZED;
    }
    
    /**
     * ApiResponse를 JSON으로 변환하여 응답에 작성
     */
    private void writeResponse(HttpServletResponse response, int status, ApiResponse<Object> apiResponse) 
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        
        String json = objectMapper.writeValueAsString(apiResponse);
        response.getWriter().write(json);
        response.getWriter().flush();
    }
}
