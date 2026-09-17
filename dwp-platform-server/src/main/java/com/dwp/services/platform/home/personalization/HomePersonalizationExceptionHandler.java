package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        HomeViewController.class,
        HomeTemplateController.class,
        HomeComposerController.class
})
class HomePersonalizationExceptionHandler {
    private static final String CORRELATION = "X-Correlation-ID";

    @ExceptionHandler(HomeViewConflictException.class)
    ResponseEntity<ApiResponse<HomeViewDtos.HomeViewConflictResponse>> conflict(
            HomeViewConflictException exception,
            HttpServletRequest request) {
        ErrorCode code = ErrorCode.HOME_VIEW_VERSION_CONFLICT;
        return ResponseEntity.status(code.getHttpStatus()).body(ApiResponse.error(
                code,
                code.getMessage(),
                exception.conflict(),
                request.getHeader(CORRELATION)));
    }
}
