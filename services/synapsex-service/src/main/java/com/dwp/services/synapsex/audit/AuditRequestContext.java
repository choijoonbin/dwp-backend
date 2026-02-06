package com.dwp.services.synapsex.audit;

import com.dwp.core.constant.HeaderConstants;
import jakarta.servlet.http.HttpServletRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * P0 Audit: Request context 추출 (ip, userAgent, traceId, gatewayRequestId).
 * Controller에서 audit 로깅 시 사용.
 */
public final class AuditRequestContext {

    private AuditRequestContext() {}

    public static String getIpAddress(HttpServletRequest req) {
        return req != null ? req.getRemoteAddr() : null;
    }

    public static String getUserAgent(HttpServletRequest req) {
        return req != null ? req.getHeader("User-Agent") : null;
    }

    public static String getGatewayRequestId(HttpServletRequest req) {
        return req != null ? req.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID) : null;
    }

    public static String getTraceId(HttpServletRequest req) {
        return req != null ? req.getHeader(HeaderConstants.X_TRACE_ID) : null;
    }

    /** LIST 조회용 tags (page, size, sort, order + 선택적 필터). PII 금지. */
    public static Map<String, Object> listTags(int page, int size, String sort, String order, Map<String, Object> filters) {
        Map<String, Object> tags = new HashMap<>();
        tags.put("page", page);
        tags.put("size", size);
        tags.put("sort", sort != null ? sort : "createdAt");
        tags.put("order", order != null ? order : "desc");
        if (filters != null && !filters.isEmpty()) {
            tags.putAll(filters);
        }
        return tags;
    }
}
