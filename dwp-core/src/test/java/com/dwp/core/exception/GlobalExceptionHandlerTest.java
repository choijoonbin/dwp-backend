package com.dwp.core.exception;

import com.dwp.core.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void objectLevelValidationErrorRemainsAValidationResponse() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.E4000", Locale.KOREAN, "입력값 검증에 실패했습니다.");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(messages);
        BindException exception = new BindException(new Object(), "request");
        exception.addError(new ObjectError("request", "요청 조합이 올바르지 않습니다."));

        ResponseEntity<ApiResponse<Map<String, String>>> response = handler.handleBindException(
                exception,
                new MockHttpServletRequest(),
                Locale.KOREAN);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("입력값 검증에 실패했습니다.");
        assertThat(response.getBody().getData())
                .containsEntry("request", "요청 조합이 올바르지 않습니다.");
    }
}
