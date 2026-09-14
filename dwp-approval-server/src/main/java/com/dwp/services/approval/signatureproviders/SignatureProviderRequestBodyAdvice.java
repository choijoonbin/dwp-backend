package com.dwp.services.approval.signatureproviders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Validate original bytes before Jackson's Unicode auto-detection can hide an invalid wire encoding. */
@ControllerAdvice
public final class SignatureProviderRequestBodyAdvice extends RequestBodyAdviceAdapter {
    @Override public boolean supports(MethodParameter parameter, Type targetType,
                                      Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType instanceof Class<?> type && SignatureProviderJson.INPUTS.contains(type);
    }

    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter,
                                                    Type targetType, Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        try {
            MediaType contentType = input.getHeaders().getContentType();
            if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                    || contentType.getCharset() != null && !StandardCharsets.UTF_8.equals(contentType.getCharset()))
                throw new IOException("Signature provider input requires UTF-8 application/json");
            byte[] raw = input.getBody().readNBytes(SignatureProviderJson.MAX_BODY_BYTES + 1);
            SignatureProviderJson.requireUtf8(raw);
            return new HttpInputMessage() {
                @Override public InputStream getBody() { return new ByteArrayInputStream(raw); }
                @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
            };
        } catch (IOException | IllegalArgumentException malformed) {
            throw new HttpMessageNotReadableException("Invalid signature provider request body", malformed, input);
        }
    }
}
