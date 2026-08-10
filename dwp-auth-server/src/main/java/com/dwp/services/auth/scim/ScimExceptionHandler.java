package com.dwp.services.auth.scim;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(basePackages = "com.dwp.services.auth.scim")
public class ScimExceptionHandler {

    private static final MediaType SCIM_JSON = MediaType.parseMediaType("application/scim+json");

    @ExceptionHandler(ScimException.class)
    public ResponseEntity<ScimModels.ErrorResponse> handle(ScimException exception) {
        return ResponseEntity.status(exception.status())
                .contentType(SCIM_JSON)
                .body(new ScimModels.ErrorResponse(
                        List.of(ScimModels.ERROR),
                        String.valueOf(exception.status()),
                        exception.scimType(),
                        exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ScimModels.ErrorResponse> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest()
                .contentType(SCIM_JSON)
                .body(new ScimModels.ErrorResponse(
                        List.of(ScimModels.ERROR),
                        "400",
                        "invalidValue",
                        "The SCIM request body is invalid."));
    }
}
