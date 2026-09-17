package com.dwp.services.approval.templates;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.springframework.web.servlet.HandlerMapping;

final class ApprovalTemplateHttpBoundary {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private ApprovalTemplateHttpBoundary() { }

    static void query(HttpServletRequest request, Set<String> allowed, Set<String> repeatable) {
        Map<String, String[]> parameters = request.getParameterMap();
        if (!allowed.containsAll(parameters.keySet())) throw invalid();
        for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
            int count = entry.getValue().length;
            if (count == 0 || !repeatable.contains(entry.getKey()) && count != 1 || count > 20) throw invalid();
        }
        canonicalPaths(request);
    }

    static String idempotencyKey(HttpServletRequest request) {
        String value = header(request, "Idempotency-Key", true);
        if (!value.matches("[A-Za-z0-9._:-]{1,200}")) throw invalid();
        return value;
    }

    static String correlationId(HttpServletRequest request) {
        String value = header(request, "X-Correlation-ID", false);
        if (value != null && value.length() > 200) throw invalid();
        return value;
    }

    static void expectedVersion(HttpServletRequest request, long bodyVersion) {
        String raw = header(request, "X-DWP-Expected-Object-Version", true);
        if (!raw.matches("0|[1-9][0-9]{0,15}")) throw invalid();
        try {
            long headerVersion = Long.parseLong(raw);
            if (headerVersion > MAX_SAFE_INTEGER) throw invalid();
            if (headerVersion != bodyVersion) throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static String header(HttpServletRequest request, String name, boolean required) {
        var values = Collections.list(request.getHeaders(name));
        if (values.isEmpty() && !required) return null;
        if (values.size() != 1 || values.getFirst().isBlank()
                || !values.getFirst().equals(values.getFirst().strip())
                || values.getFirst().codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return values.getFirst();
    }

    private static void canonicalPaths(HttpServletRequest request) {
        Object value = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(value instanceof Map<?, ?> variables)) return;
        for (Map.Entry<?, ?> entry : variables.entrySet()) {
            if (!entry.getKey().toString().endsWith("Id")) continue;
            if (!(entry.getValue() instanceof String text)
                    || !text.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
                throw invalid();
            }
        }
    }

    private static BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE); }
}
