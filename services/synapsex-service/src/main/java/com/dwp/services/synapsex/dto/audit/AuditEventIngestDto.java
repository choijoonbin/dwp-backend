package com.dwp.services.synapsex.dto.audit;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Redis Pub/Sub 수신용 AuditEvent JSON DTO.
 * Aura에서 audit:events:ingest 채널로 발행하는 메시지 형식.
 * snake_case / camelCase 모두 지원.
 */
@Data
public class AuditEventIngestDto {

    @JsonProperty("tenant_id")
    @JsonAlias({"tenantId"})
    private Long tenantId;

    @JsonProperty("event_category")
    @JsonAlias({"eventCategory"})
    private String eventCategory;

    @JsonProperty("event_type")
    @JsonAlias({"eventType"})
    private String eventType;

    @JsonProperty("resource_type")
    @JsonAlias({"resourceType"})
    private String resourceType;

    @JsonProperty("resource_id")
    @JsonAlias({"resourceId"})
    private String resourceId;

    @JsonProperty("created_at")
    @JsonAlias({"createdAt"})
    private String createdAt;  // ISO 8601 문자열

    @JsonProperty("actor_type")
    @JsonAlias({"actorType"})
    private String actorType;

    @JsonProperty("actor_user_id")
    @JsonAlias({"actorUserId"})
    private Long actorUserId;

    @JsonProperty("actor_agent_id")
    @JsonAlias({"actorAgentId"})
    private String actorAgentId;

    @JsonProperty("actor_display_name")
    @JsonAlias({"actorDisplayName"})
    private String actorDisplayName;

    private String channel;
    private String outcome;
    private String severity;

    @JsonProperty("before_json")
    @JsonAlias({"beforeJson"})
    private Map<String, Object> beforeJson;

    @JsonProperty("after_json")
    @JsonAlias({"afterJson"})
    private Map<String, Object> afterJson;

    @JsonProperty("diff_json")
    @JsonAlias({"diffJson"})
    private Map<String, Object> diffJson;

    @JsonProperty("evidence_json")
    @JsonAlias({"evidenceJson"})
    private Map<String, Object> evidenceJson;

    private Map<String, Object> tags;

    @JsonProperty("ip_address")
    @JsonAlias({"ipAddress"})
    private String ipAddress;

    @JsonProperty("user_agent")
    @JsonAlias({"userAgent"})
    private String userAgent;

    @JsonProperty("gateway_request_id")
    @JsonAlias({"gatewayRequestId"})
    private String gatewayRequestId;

    @JsonProperty("trace_id")
    @JsonAlias({"traceId"})
    private String traceId;

    @JsonProperty("span_id")
    @JsonAlias({"spanId"})
    private String spanId;
}
