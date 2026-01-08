package com.dwp.core.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    // 공통 에러 (1000번대)
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "E1000", "내부 서버 오류가 발생했습니다."),
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "E1001", "잘못된 입력값입니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "E1002", "허용되지 않은 HTTP 메서드입니다."),
    HANDLE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "E1003", "접근이 거부되었습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "E1004", "요청한 리소스를 찾을 수 없습니다."),
    
    // 인증/인가 에러 (2000번대)
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "E2000", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "E2001", "권한이 없습니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "E2002", "토큰이 만료되었습니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "E2003", "유효하지 않은 토큰입니다."),
    
    // 비즈니스 로직 에러 (3000번대)
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "E3000", "엔티티를 찾을 수 없습니다."),
    DUPLICATE_ENTITY(HttpStatus.CONFLICT, "E3001", "이미 존재하는 엔티티입니다."),
    INVALID_STATE(HttpStatus.BAD_REQUEST, "E3002", "잘못된 상태입니다."),
    
    // 검증 에러 (4000번대)
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "E4000", "입력값 검증에 실패했습니다."),
    MISSING_REQUIRED_FIELD(HttpStatus.BAD_REQUEST, "E4001", "필수 필드가 누락되었습니다."),
    INVALID_FORMAT(HttpStatus.BAD_REQUEST, "E4002", "잘못된 형식입니다."),
    
    // 외부 서비스 에러 (5000번대)
    EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "E5000", "외부 서비스 오류가 발생했습니다."),
    EXTERNAL_SERVICE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "E5001", "외부 서비스 응답 시간이 초과되었습니다.");
    
    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
    
    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}
