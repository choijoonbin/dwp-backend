package com.dwp.services.auth.dto;

import lombok.*;

/**
 * API 호출 이력 저장 요청 (Gateway internal용)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiCallHistoryRequest {
    private Long tenantId;
    private Long userId;
    private String agentId;
    private String method;
    private String path;
    private String queryString;
    private Integer statusCode;
    private Long latencyMs;
    private Long requestSizeBytes;
    private Long responseSizeBytes;
    private String ipAddress;
    private String userAgent;
    private String traceId;
    private String errorCode;
    private String source;
}
