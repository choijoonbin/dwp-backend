package com.dwp.core.exception;

import com.dwp.core.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void missingQueryParameterRemainsAClientValidationError() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage(
                "request.missing-parameter",
                Locale.KOREAN,
                "필수 파라미터 ''{0}''이(가) 누락되었습니다.");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(messages);

        ResponseEntity<ApiResponse<Object>> response = handler.handleMissingRequestParameter(
                new MissingServletRequestParameterException("from", "OffsetDateTime"),
                new MockHttpServletRequest(),
                Locale.KOREAN);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .isEqualTo("필수 파라미터 'from'이(가) 누락되었습니다.");
    }

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

    @Test
    void unknownRouteReturnsNotFoundInsteadOfInternalServerError() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.E1004", Locale.ENGLISH, "The requested resource was not found.");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(messages);

        ResponseEntity<ApiResponse<Object>> response = handler.handleNoResourceFoundException(
                new NoResourceFoundException(HttpMethod.GET, "/v1/unknown"),
                new MockHttpServletRequest(),
                Locale.ENGLISH);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .isEqualTo("The requested resource was not found.");
    }

    @Test
    void unsupportedContentTypeReturnsUnsupportedMediaTypeInsteadOfInternalServerError() {
        StaticMessageSource messages = new StaticMessageSource();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(messages);

        ResponseEntity<ApiResponse<Object>> response = handler.handleHttpMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException("text/plain"),
                new MockHttpServletRequest(),
                Locale.ENGLISH);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("E4002");
        assertThat(response.getBody().getMessage())
                .isEqualTo("The request Content-Type is not supported.");
    }
}
