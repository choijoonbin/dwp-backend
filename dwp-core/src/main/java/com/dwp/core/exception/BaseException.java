package com.dwp.core.exception;

import com.dwp.core.common.ErrorCode;
import lombok.Getter;

@Getter
public class BaseException extends RuntimeException {
    private final ErrorCode errorCode;
    
    public BaseException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
    
    public BaseException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
    
    public BaseException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
    
    // 하위 호환성을 위한 생성자 (deprecated)
    @Deprecated
    public BaseException(String code, String message) {
        super(message);
        this.errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
    }
    
    @Deprecated
    public BaseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
    }
}
