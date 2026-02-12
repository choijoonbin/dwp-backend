package com.dwp.services.synapsex.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * POST /api/synapse/agents — 신규 에이전트 생성 요청
 * agent_key: Snake Case 권장 (finance_aura, hr_aura). Aura 호출 시 Key로 사용.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateAgentRequest {

    /** Snake Case 권장. tenant 내 unique */
    @NotBlank(message = "agent_key는 필수입니다.")
    @Size(min = 1, max = 100)
    @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "agent_key는 소문자, 숫자, 언더스코어만 허용됩니다 (Snake Case).")
    private String agentKey;

    @NotBlank(message = "name은 필수입니다.")
    @Size(max = 255)
    private String name;

    @Size(max = 100)
    private String domain;

    @Size(max = 255)
    private String modelName;

    private BigDecimal temperature;
    private Integer maxTokens;

    /** 초기 시스템 프롬프트. 없으면 빈 문자열로 저장 */
    private String systemInstruction;

    /** 매핑할 도구 tool_id 목록 (선택) */
    private List<Long> toolIds;
}
