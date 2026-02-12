# 에이전트 스튜디오 API 규격 (프론트 전달용)

> **작성일**: 2026-02-12  
> **Base URL**: Gateway 8080 경유 → `/api/synapse/agents`  
> **공통**: 모든 요청에 `X-Tenant-ID`(필수), `Authorization: Bearer {JWT}`(필수)

---

## 공통

### 필수 헤더
| 헤더 | 타입 | 필수 | 설명 |
|------|------|------|------|
| X-Tenant-ID | Long | ✅ | 테넌트 식별자 |
| Authorization | Bearer {JWT} | ✅ | 인증 토큰 |
| X-User-ID | Long | ⭕ | 사용자 식별 (감사 등, 선택) |

### 응답 래퍼 (ApiResponse&lt;T&gt;)
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { ... },
  "success": true,
  "timestamp": "2026-02-12T12:00:00"
}
```
- 목록/상세/생성/수정: `data`에 해당 DTO 또는 배열
- 삭제: `data`는 null, `message`로 안내

### 에러
| HTTP | errorCode | 설명 |
|------|-----------|------|
| 400 | VALIDATION_ERROR, INVALID_CODE, PROMPT_VALIDATION_ERROR | 파라미터/코드/프롬프트 검증 실패 |
| 404 | ENTITY_NOT_FOUND | 에이전트 없음 또는 비활성 |
| 409 | AGENT_KEY_DUPLICATE | agent_key 중복 (생성 시) |

---

## 1. 목록 조회

**GET** `/api/synapse/agents`

- **용도**: 사이드바 카드 목록 등. **활성(is_active=true) 에이전트만** 반환.
- **Response**: `ApiResponse<List<AgentDetailDto>>`

**AgentDetailDto** (목록 항목·상세 공통)
| 필드 | 타입 | 설명 |
|------|------|------|
| agentId | Long | PK |
| agentKey | String | Aura 호출 키 (snake_case) |
| name | String | 표시명 |
| domain | String | AGENT_DOMAIN 코드 (FINANCE, HR, DEV 등) |
| modelName | String | LLM_MODEL 코드 |
| temperature | BigDecimal | 0~1 |
| maxTokens | Integer | |
| isActive | Boolean | |
| systemInstruction | String | 현재 프롬프트 본문 (상세 시 사용) |
| promptVersion | Integer | 현재 프롬프트 버전 |
| toolIds | List&lt;Long&gt; | 매핑된 도구 tool_id 목록 |
| createdAt | Instant | |
| updatedAt | Instant | |

---

## 2. 상세 조회

**GET** `/api/synapse/agents/{id}`

- **용도**: 에이전트 선택 시 4탭 로드. tenant 소유 검증 후 반환 (비활성 포함).
- **Path**: `id` = agentId (Long)
- **Response**: `ApiResponse<AgentDetailDto>`
- **404**: 해당 id가 없거나 다른 tenant 소유

---

## 3. 등록(생성)

**POST** `/api/synapse/agents`

- **용도**: 신규 에이전트 등록.
- **Request**: `CreateAgentRequest` (JSON)

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| agentKey | String | ✅ | snake_case, tenant 내 unique (예: finance_aura) |
| name | String | ✅ | 표시명 |
| domain | String | ⭕ | AGENT_DOMAIN 코드 (app_codes) |
| modelName | String | ⭕ | LLM_MODEL 코드 |
| temperature | BigDecimal | ⭕ | |
| maxTokens | Integer | ⭕ | |
| systemInstruction | String | ⭕ | 초기 시스템 프롬프트 (없으면 빈 문자열) |
| toolIds | List&lt;Long&gt; | ⭕ | 매핑할 도구 tool_id 목록 |

- **agentKey**: `^[a-z][a-z0-9_]*$` (소문자, 숫자, 언더스코어만)
- **Response**: `ApiResponse<AgentDetailDto>` (생성된 에이전트 상세)
- **409**: agent_key 중복

---

## 4. 수정(저장·배포)

**PUT** `/api/synapse/agents/{id}`

- **용도**: "변경 사항 저장 및 배포" 버튼. 전송된 필드만 갱신.
- **Path**: `id` = agentId
- **Request**: `UpdateAgentRequest` (JSON, 모두 선택)

| 필드 | 타입 | 설명 |
|------|------|------|
| name | String | |
| domain | String | app_codes AGENT_DOMAIN |
| modelName | String | app_codes LLM_MODEL |
| temperature | BigDecimal | |
| maxTokens | Integer | |
| isActive | Boolean | |
| systemInstruction | String | 변경 시 새 prompt_history 버전 추가, is_current 전환 |
| toolIds | List&lt;Long&gt; | **전체 교체**. null이면 기존 유지 |

- **Response**: `ApiResponse<AgentDetailDto>` (수정 후 상세)

---

## 5. 삭제

**DELETE** `/api/synapse/agents/{id}`

- **용도**: 에이전트 삭제. **Soft delete** (is_active = false). 목록/설정 조회에서 제외됨.
- **Path**: `id` = agentId
- **Response**: `ApiResponse<Void>` (data null, message로 안내)
- **404**: 에이전트 없음 또는 다른 tenant

---

## 6. 카탈로그·도구·지식 (등록/수정 폼 옵션용)

| Method | Path | 용도 |
|--------|------|------|
| GET | `/api/synapse/agents/catalog` | **통합 카탈로그**: domains, docTypes, models(app_codes), tools 한 번에. FE 옵션 동적 렌더링용. |
| GET | `/api/synapse/agents/tools` | **도구 카탈로그**: Aura 등록 가능 전체 도구 (toolId, toolName, description, schemaJson). FE '도구' 탭. |
| GET | `/api/synapse/agents/knowledge` | **지식 베이스 카탈로그**: RAG 문서 목록. FE '지식' 탭. Query: page, size. |

- **catalog** Response: `AgentCatalogResponseDto` (domains, docTypes, models, tools 각각 KeyValueItem[] 또는 도구 목록)
- **tools** Response: `List<AgentToolCatalogItemDto>` (toolId, toolName, description, schemaJson)
- **knowledge** Response: 지식 항목 목록 (docId, title, sourceType, docType, status, createdAt 등)

---

## 7. 에이전트 설정 (Aura 런타임용)

**GET** `/api/synapse/agents/{id}/config`

- **용도**: Aura 엔진이 에이전트 조립 시 호출. **활성만** 반환 (비활성 시 404).
- **Response**: `AgentConfigResponseDto` (agentId, agentKey, name, domain, model, systemInstruction, **tools**: toolName, description, schemaJson)

---

## 현재 구현·API 적용 상태 요약

| 기능 | API | BE 구현 | 비고 |
|------|-----|---------|------|
| 목록 조회 | GET /api/synapse/agents | ✅ | 활성만 |
| 상세 조회 | GET /api/synapse/agents/{id} | ✅ | |
| 등록(생성) | POST /api/synapse/agents | ✅ | |
| 수정(저장·배포) | PUT /api/synapse/agents/{id} | ✅ | |
| 삭제 | DELETE /api/synapse/agents/{id} | ✅ | Soft delete |
| 통합 카탈로그 | GET /api/synapse/agents/catalog | ✅ | |
| 도구 카탈로그 | GET /api/synapse/agents/tools | ✅ | |
| 지식 카탈로그 | GET /api/synapse/agents/knowledge | ✅ | |
| Aura 설정 | GET /api/synapse/agents/{id}/config | ✅ | 런타임용 |

위 규격으로 FE에서 등록·조회·수정·삭제·카탈로그 연동 시 사용하시면 됩니다.
