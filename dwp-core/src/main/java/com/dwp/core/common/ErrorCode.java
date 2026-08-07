package com.dwp.core.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "E1000", "내부 서버 오류가 발생했습니다."),
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "E1001", "잘못된 입력값입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "E1004", "요청한 리소스를 찾을 수 없습니다."),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "E2000", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "E2001", "권한이 없습니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "E2003", "유효하지 않은 토큰입니다."),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "E2004", "잘못된 자격 증명입니다."),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "E2005", "인증이 필요합니다."),
    TENANT_MISSING(HttpStatus.BAD_REQUEST, "E2006", "테넌트 정보가 필요합니다."),
    TENANT_MISMATCH(HttpStatus.FORBIDDEN, "E2007", "테넌트 정보가 일치하지 않습니다."),

    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "E3000", "엔티티를 찾을 수 없습니다."),
    INVALID_STATE(HttpStatus.BAD_REQUEST, "E3002", "잘못된 상태입니다."),

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "E4000", "입력값 검증에 실패했습니다."),
    INVALID_FORMAT(HttpStatus.BAD_REQUEST, "E4002", "잘못된 형식입니다."),

    EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "E5000", "외부 서비스 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}
