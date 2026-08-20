package com.dwp.services.messaging.attachment;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class AttachmentTransferGuardFilter extends OncePerRequestFilter {

    private static final Pattern CONTENT_PATH = Pattern.compile(
            "^/v1/conversations/[0-9a-fA-F-]{36}/attachments/[0-9a-fA-F-]{36}/content$");

    private final long maximumBytes;
    private final Semaphore transferSlots;
    private final ObjectMapper objectMapper;

    public AttachmentTransferGuardFilter(
            AttachmentProperties properties,
            ObjectMapper objectMapper) {
        this.maximumBytes = Math.multiplyExact((long) properties.maximumTransferMb(), 1024L * 1024L);
        this.transferSlots = new Semaphore(properties.maximumConcurrentTransfers(), true);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!CONTENT_PATH.matcher(request.getRequestURI()).matches()) return true;
        return !HttpMethod.PUT.matches(request.getMethod())
                && !(HttpMethod.GET.matches(request.getMethod())
                && request.getParameter("downloadToken") != null);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (HttpMethod.PUT.matches(request.getMethod()) && !validUploadLength(request, response)) {
            return;
        }
        if (!transferSlots.tryAcquire()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
            writeError(
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "The attachment transfer limit is temporarily busy. Retry shortly.");
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            transferSlots.release();
        }
    }

    private boolean validUploadLength(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0) {
            writeError(
                    response,
                    HttpStatus.LENGTH_REQUIRED,
                    "A Content-Length header is required for attachment uploads.");
            return false;
        }
        if (contentLength == 0 || contentLength > maximumBytes) {
            writeError(
                    response,
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "The attachment exceeds the configured transfer limit.");
            return false;
        }
        return true;
    }

    private void writeError(
            HttpServletResponse response,
            HttpStatus status,
            String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE, message));
    }
}
