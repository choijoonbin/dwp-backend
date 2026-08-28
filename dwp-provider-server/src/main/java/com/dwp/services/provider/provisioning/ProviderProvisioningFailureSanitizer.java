package com.dwp.services.provider.provisioning;

import com.dwp.core.exception.BaseException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ProviderProvisioningFailureSanitizer {

    private static final int MAX_MESSAGE_LENGTH = 1000;

    public Failure sanitize(RuntimeException exception) {
        String code;
        if (exception instanceof RestClientResponseException response) {
            code = "HTTP_" + response.getStatusCode().value();
        } else if (exception instanceof BaseException baseException) {
            code = baseException.getErrorCode().name();
        } else {
            code = "PROVISIONING_FAILED";
        }
        String message;
        if (exception instanceof RestClientResponseException response) {
            message = "Downstream provisioning failed (HTTP "
                    + response.getStatusCode().value() + ").";
        } else if (exception instanceof DataAccessException) {
            message = "Provider state persistence failed. Review the correlated service trace.";
        } else if (exception instanceof BaseException) {
            message = exception.getMessage();
        } else {
            message = "Provider step failed. Review the correlated service trace.";
        }
        if (message == null || message.isBlank()) {
            message = "Provider provisioning failed. Review the correlated service trace.";
        }
        return new Failure(code, message.length() <= MAX_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_MESSAGE_LENGTH));
    }

    public record Failure(String code, String message) {
    }
}
