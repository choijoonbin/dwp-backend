package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * API 호출 이력 엔티티 (sys_api_call_histories)
 */
@Entity
@Table(name = "sys_api_call_histories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiCallHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "api_call_history_id")
    private Long apiCallHistoryId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "agent_id", length = 100, columnDefinition = "VARCHAR(100)")
    private String agentId;

    @Column(name = "source", length = 20, columnDefinition = "VARCHAR(20)")
    private String source;

    @Column(name = "method", nullable = false, length = 10, columnDefinition = "VARCHAR(10)")
    private String method;

    @Column(name = "path", nullable = false, length = 500, columnDefinition = "VARCHAR(500)")
    private String path;

    @Column(name = "query_string", columnDefinition = "TEXT")
    private String queryString;

    @Column(name = "status_code", nullable = false)
    private Integer statusCode;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "request_size_bytes")
    private Long requestSizeBytes;

    @Column(name = "response_size_bytes")
    private Long responseSizeBytes;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @Column(name = "error_code", length = 100)
    private String errorCode;
}
