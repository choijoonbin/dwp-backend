# Aura 전달 에이전트 관련 API 명세서 (BE ↔ Aura 규격)

> **목적**: 백엔드가 **Aura(엔진)** 에게 제공하는 에이전트 관련 API 규격. 양쪽(BE·Aura)이 이 문서를 기준으로 경로·필드명·응답 형식을 맞춥니다.  
> **작성일**: 2026-02-12

---

## 문서 위치 요약

| 구분 | 문서 | 용도 |
|------|------|------|
| **에이전트 설정(조립)** | 본 문서 §1 + [AGENT_STUDIO_AGENT_CONFIG_CONTRACT.md](AGENT_STUDIO_AGENT_CONFIG_CONTRACT.md) | Aura가 런타임에 에이전트 조립 시 호출하는 config API |
| **도구 실행(Tool API)** | 본 문서 §2 + [AGENT_TOOL_API_SPEC.md](../../services/synapsex-service/docs/20260203/AGENT_TOOL_API_SPEC.md) | Aura가 get_case, search_documents 등 도구 실행 시 호출하는 agent-tools API |
| **도구 명칭·계약** | [AGENT_STUDIO_NAMING_AND_SANDBOX_CONTRACT.md](AGENT_STUDIO_NAMING_AND_SANDBOX_CONTRACT.md), [TOOL_INVENTORY_FOR_BACKEND.md](../handoff/TOOL_INVENTORY_FOR_BACKEND.md) | tool_name 일치, 샌드박스 등 |

---

## 1. 에이전트 설정 조립 API (Aura 런타임)

Aura 엔진이 에이전트를 조립할 때 **1회** 호출하는 API.

### 1.1 요청

**방식 A — agent_key로 조회 (Aura 권장)**  
Aura는 숫자 PK(agentId) 없이 agent_key만으로 설정을 받을 수 있습니다.

| 항목 | 값 |
|------|-----|
| **Method** | GET |
| **경로 (Gateway)** | `/api/v1/agents/config` |
| **Query** | `agent_key` (필수). 예: `agent_key=finance_aura` |
| **헤더** | X-Tenant-ID, Authorization 필수 |

**방식 B — agentId로 조회**

| 항목 | 값 |
|------|-----|
| **Method** | GET |
| **경로 (Gateway)** | `/api/v1/agents/{id}/config` |
| **경로 (Synapse 서비스)** | `/api/synapse/agents/{id}/config` (Gateway가 rewrite) |
| **Path** | `id` = agentId (Long). tenant 소유·활성(is_active=true)인 에이전트만 반환. |

### 1.2 필수 헤더

| 헤더 | 필수 | 설명 |
|------|------|------|
| X-Tenant-ID | ✅ | 테넌트 식별자 (에이전트 tenant와 일치해야 함) |
| Authorization | ✅ | Bearer JWT |

### 1.3 응답 (AgentConfigResponseDto)

**Content-Type**: application/json, ApiResponse&lt;T&gt; 래퍼.

**data** 필드 구조 (JSON 필드명 그대로 사용):

| 필드 | 타입 | 설명 |
|------|------|------|
| agentId | Long | 에이전트 PK |
| agentKey | String | Aura 호출 시 사용하는 키 (snake_case, 예: finance_aura) |
| name | String | 표시명 |
| domain | String | AGENT_DOMAIN 코드 (FINANCE, HR, DEV 등) |
| model | object | 모델 설정 |
| model.modelName | String | LLM_MODEL 코드 (gpt-4o, claude-3-5-sonnet, r1) |
| model.temperature | BigDecimal | 0~1 |
| model.maxTokens | Integer | |
| systemInstruction | String | 현재 시스템 프롬프트 전문 (agent_prompt_history is_current=true). Aura는 이 값이 있으면 코드 내부 기본값 대신 사용. |
| version | Integer | agent_prompt_history 현재 버전. Aura 콜백 등에서 agent_id·version 전달용. |
| tools | array | 이 에이전트에 매핑된 도구 목록. **tools[].toolName**을 모아 agent_tool_mapping(도구 이름 목록)으로 사용 가능. |

**tools[]** 각 항목:

| 필드 | 타입 | 설명 |
|------|------|------|
| toolName | String | Aura @tool 함수명과 **완전 동일** (예: get_case, web_search) |
| description | String | 도구 설명 (선택) |
| schemaJson | object | JSON Schema 등 파라미터 규격 (선택) |

### 1.4 ApiResponse 래퍼

- 응답은 **ApiResponse&lt;T&gt;** 래퍼. 실제 설정은 **data** 키에 담깁니다.
- `data` 내부 필드명은 **camelCase** (agentId, agentKey, systemInstruction, model.modelName, tools[].toolName 등).

### 1.5 에러

| HTTP | 조건 |
|------|------|
| 404 | 에이전트 없음 또는 비활성(is_active=false) |

---

## 2. 도구 실행 API (Agent-Tools, Aura가 도구 호출 시)

Aura가 config의 **tools[].toolName**에 따라 실제 HTTP 호출을 보내는 Base URL 및 엔드포인트.

### 2.1 Base URL

- **Gateway 경유**: `http://{gateway-host}:8080/api/synapse/agent-tools`
- **Aura 설정**: 위 URL을 Base로 두고, 아래 경로를 붙여 호출 (예: GET `/api/synapse/agent-tools/cases/123`).

### 2.2 공통 헤더

| 헤더 | 필수 | 설명 |
|------|------|------|
| X-Tenant-ID | ✅ | 테넌트 식별자 |
| X-Agent-ID | ⭕ | Aura 에이전트 세션 ID (감사 시 ACTOR_AGENT) |
| Authorization | ✅ | Bearer JWT |

### 2.3 tool_name ↔ API 매핑 (Finance 도구)

| tool_name (config 응답) | Method | 경로 (Base 제외) | 비고 |
|-------------------------|--------|-------------------|------|
| get_case | GET | /agent-tools/cases/{caseId} | 케이스 상세 |
| search_documents | GET | /agent-tools/documents | Query: bukrs, gjahr, page, size 등 |
| get_document | GET | /agent-tools/documents/{bukrs}/{belnr}/{gjahr} | 전표 상세 |
| get_entity | GET | /agent-tools/entities/{entityId} | Entity 360 |
| get_open_items | GET | /agent-tools/open-items | Query: type, overdueBucket, page, size |
| get_lineage | GET | /agent-tools/lineage?caseId={caseId} | caseId 필수 |
| web_search | - | (Aura 내부 또는 외부 Tavily 등) | BE agent-tools 아님 |
| simulate_action | POST | /agent-tools/actions/simulate | Body: caseId, actionType, payload |
| propose_action | POST | /agent-tools/actions/propose | Body: caseId, actionType, payload |
| execute_action | POST | /agent-tools/actions/{actionId}/execute | 승인 완료 후 actionId로 실행 |

- **전체 경로 예**: `GET http://{gateway}:8080/api/synapse/agent-tools/cases/85114`

### 2.4 상세 명세

- **Read**: [AGENT_TOOL_API_SPEC.md](../../services/synapsex-service/docs/20260203/AGENT_TOOL_API_SPEC.md) — GET cases, documents, entities, open-items, lineage
- **Write**: 동일 문서 — POST actions/simulate, actions/propose, actions/{actionId}/execute (Request/Response JSON 포함)

---

## 3. 규격 맞춤 체크리스트 (BE ↔ Aura)

- [ ] **config API**: Aura는 **agent_key만 알 때** `GET /api/v1/agents/config?agent_key=finance_aura` (X-Tenant-ID 헤더 필수). agentId를 쓰는 경우 `GET /api/v1/agents/{id}/config`.
- [ ] **응답 필드명**: `systemInstruction`, `agentKey`, `model.modelName`, `tools[].toolName` 등 JSON 키 동일.
- [ ] **tool_name 일치**: config의 `tools[].toolName` = Aura @tool 함수명 = agent_tool_inventory.tool_name. [TOOL_INVENTORY_FOR_BACKEND.md](../handoff/TOOL_INVENTORY_FOR_BACKEND.md) 참고.
- [ ] **agent-tools Base URL**: Aura는 `http://{gateway}:8080/api/synapse/agent-tools` 로 설정 후 위 표 경로 사용.
- [ ] **에러 처리**: config 404 시 에이전트 미배포/비활성. agent-tools 4xx/5xx 시 Aura는 도구 실패로 처리.

이 문서를 Aura 측과 공유해 경로·필드명·에러를 맞추면 됩니다.

---

## Aura 반영 상태

- **config**: Aura는 `GET /api/v1/agents/config?agent_key=...` 호출, ApiResponse.data 추출, camelCase → snake_case 매핑(agentKey→agent_id, systemInstruction→system_instruction, model.modelName→model_name), tools[].toolName → agent_tool_mapping 반영 완료.
- **agent_id 파라미터**: Aura의 `agent_id` 인자가 백엔드 쿼리 `agent_key`로 그대로 전달됨. 백엔드에 등록된 agent_key(예: finance_aura)와 호출 시 사용하는 agent_id를 일치시키면 됨.

---

## Aura 추가 확인 사항 (선택 점검)

config 연동 완료 후, 아래 항목만 한 번씩 확인해 주시면 됩니다.

| # | 항목 | 백엔드 기준 | 확인 요청 |
|---|------|-------------|-----------|
| 1 | **agent-tools 메서드·파라미터** | `search_documents` → **GET** `/agent-tools/documents` (query: bukrs, gjahr, page, size 등). `get_open_items` → **GET** `/agent-tools/open-items` (query: type, overdueBucket, page, size). POST+body 아님. | Aura가 도구 호출 시 **GET+query**로 호출하는지. caseId만 있으면 get_case로 case 조회 후 bukrs/gjahr 추출해 documents 호출하는 플로우 권장. |
| 2 | **agent-tools 헤더** | X-Tenant-ID(필수), Authorization(필수), X-Agent-ID(선택, 감사 시). | agent-tools 호출 시 config와 동일하게 **X-Tenant-ID, Authorization** 전달하는지. |
| 3 | **get_lineage** | **caseId** 쿼리만 지원. belnr/gjahr/bukrs 직접 전달은 미지원. | lineage 호출 시 **caseId**만 사용하는지. (case 없으면 get_case 선행.) |
| 4 | **인식 불가 tool_name** | config의 tools[].toolName 중 엔진에 없는 것은 **에러 중단 없이 스킵**, ThoughtStream에 `"다음 도구는 스튜디오 설정에 있으나 엔진에 등록되지 않아 이번 실행에서 제외되었습니다: {도구명 목록}."` 안내. ([TOOL_NAMING_FOR_BACKEND.md](../handoff/TOOL_NAMING_FOR_BACKEND.md)) | Aura `get_tools_by_names()` 등에서 **스킵 목록 반환 + thought 문구 추가** 구현 여부. |
| 5 | **X-Sandbox: true** | 분석 스트림 요청에 **X-Sandbox: true** 있으면 Thought Chain 로그 **DB 저장 생략**. ([AGENT_STUDIO_NAMING_AND_SANDBOX_CONTRACT.md](AGENT_STUDIO_NAMING_AND_SANDBOX_CONTRACT.md) §3) | 테스트/샌드박스 세션에서 **X-Sandbox: true** 헤더를 백엔드로 전달하는지. |
| 6 | **에러 시 폴백** | config 400(agent_key 누락)·404(에이전트 없음) 시 ApiResponse.error. Aura는 기존처럼 _default_config(agent_id, version) 반환. | 404 시 _default_config 사용하는지. (선택) 400/404 구분해 로그로 남기면 디버깅에 유리. |
| 7 | **X-Tenant-ID 타입** | 헤더 값은 숫자(Long). 문자열 "1"도 대부분 파싱 가능. | tenant_id를 헤더에 숫자 또는 문자열 "1"로 보내는지. 일관되면 무방. |

위 항목은 **이미 Aura에서 반영되어 있으면** 별도 조치 없이, 미반영이 있으면 해당 항목만 맞추시면 됩니다.
