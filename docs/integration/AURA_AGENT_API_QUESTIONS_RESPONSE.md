# Aura → 백엔드 추가 질문에 대한 답변

Aura 측에서 전달한 **에이전트·도구 API 명세 검토 후 질문**에 대한 백엔드 답변입니다. 반영된 API와 규격을 기준으로 Aura 쪽 경로·필드 매핑을 진행하시면 됩니다.

---

## Aura 반영 완료 (작업 내용 공유)

Aura 측에서 아래와 같이 백엔드 답변을 반영했습니다.

| 항목 | Aura 반영 내용 |
|------|----------------|
| **호출** | `core/analysis/agent_factory.py`: URL `GET {gateway}/api/v1/agents/config?agent_key={agent_id}`, 헤더 X-Tenant-ID(필수), Authorization(context 시 get_synapse_headers()). |
| **파싱** | 응답 JSON에서 `data` 추출 → camelCase → AgentConfig 변환(`_parse_config_response()`). agentKey→agent_id, version(Integer)→version(str), systemInstruction→system_instruction, model.modelName→model_name. |
| **도구** | tools[]만 사용. tools[].toolName을 모아 agent_tool_mapping으로 설정, AgentToolItem 리스트는 tools에 설정. |
| **실패 시** | 기존처럼 _default_config(agent_id, version) 반환. |
| **설정** | `core/config.py`: agent_config_path 기본값·설명을 api/v1/agents/config, Query agent_key, Header X-Tenant-ID 필수로 수정. |

**agent_id ↔ agent_key**: Aura 호출 시 `agent_id` 인자를 그대로 **agent_key** 쿼리로 전송합니다. 예: `fetch_agent_config(agent_id="audit", tenant_id="1")` → `?agent_key=audit`. 백엔드에 `audit`이 없고 `finance_aura`만 등록된 경우, 호출하는 쪽에서 `agent_id="finance_aura"`로 넘기거나, 백엔드에서 `audit` agent_key를 추가(시드 또는 별도 에이전트)하면 됩니다.

---

## 1. Config API 호출 방식 (에이전트 식별자)

**질문**: Aura는 숫자 PK(agentId)를 모른 상태에서 **agent_key**(예: `finance_aura`)만으로 설정을 받을 수 있는지.

### 답변 (1-1) — Query 기반 Config API 지원

**지원합니다.** agent_key만으로 설정을 조회하는 API를 추가했습니다.

| 항목 | 값 |
|------|-----|
| **Method** | GET |
| **경로 (Gateway)** | `/api/v1/agents/config` |
| **Query** | `agent_key` (필수). 예: `agent_key=finance_aura` |
| **헤더** | `X-Tenant-ID` (필수), `Authorization` (필수) |

**호출 예시**
```
GET {gateway}/api/v1/agents/config?agent_key=finance_aura
Header: X-Tenant-ID: 1, Authorization: Bearer {JWT}
```

- Gateway가 `/api/v1/agents/config` → `/api/synapse/agents/config` 로 rewrite하며, **query 파라미터는 그대로 유지**됩니다.
- 응답 형식은 기존 `GET /api/v1/agents/{id}/config`와 **동일** (AgentConfigResponseDto, ApiResponse 래퍼).

### (1-2) by-key → agentId 조회 후 캐시하는 방식

필요 시 **by-key 조회**도 사용할 수 있습니다.

- **경로**: `GET /api/synapse/agents/by-key?agent_key=finance_aura` (헤더 `X-Tenant-ID` 필수)
- **응답**: 현재는 별도 by-key 전용 엔드포인트는 두지 않았고, **위 (1-1) config?agent_key=** 한 번으로 동일한 config 응답(그 안에 `agentId`, `agentKey` 포함)을 받으시면 됩니다.
- Aura에서 agentId를 캐시해 두고 이후 `GET /api/v1/agents/{id}/config`만 쓰고 싶다면, **config?agent_key=** 응답의 `data.agentId`를 저장해 두시면 됩니다.

---

## 2. Config 응답 필드명·매핑

### (2-1) version 필드

**포함됩니다.** config 응답에 **version** 필드를 추가했습니다.

| 필드 | 타입 | 의미 |
|------|------|------|
| version | Integer | **agent_prompt_history** 현재 버전(version). is_current=true인 행의 version 값. |

- Aura 콜백 등에서 `agent_id`·`version`을 함께 전달하시면 됩니다.
- agent_master 자체 버전은 없으며, **프롬프트 버전**만 제공합니다.

### (2-2) agent_tool_mapping vs tools[]

**tools[]만으로 도구 목록 사용 가능합니다.**

- 백엔드는 **tools[]** 만 내려줍니다. 별도 필드 `agent_tool_mapping`은 없습니다.
- Aura에서는 **tools[].toolName**을 모아서 agent_tool_mapping(도구 이름 목록)으로 사용하시면 됩니다.
- 즉, `agent_tool_mapping = [ t.toolName for t in data.tools ]` 로 매핑하시면 됩니다.

---

## 3. ApiResponse 래퍼 및 data 추출

### (3-1) data 키

**예. 키 이름은 `"data"` 입니다.**

```json
{
  "success": true,
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "agentId": 1,
    "agentKey": "finance_aura",
    "name": "Finance 감사 에이전트",
    "domain": "FINANCE",
    "model": { "modelName": "gpt-4o", "temperature": 0.2, "maxTokens": 4096 },
    "systemInstruction": "...",
    "version": 1,
    "tools": [ { "toolName": "get_case", "description": "...", "schemaJson": { ... } }, ... ]
  },
  "timestamp": "2026-02-12T..."
}
```

### (3-2) data 내부 필드명

**data 안의 필드명은 명세와 동일하게 camelCase로 내려갑니다.**

- agentId, agentKey, systemInstruction, model.modelName, tools[].toolName 등 **전부 camelCase**.
- Aura에서 `data`를 꺼낸 뒤 snake_case로 변환해 수신하셔도 됩니다.

---

## 4. Agent-Tools Base URL (도구 실행)

### (4-1) 경로 확인

**맞습니다.**

- **Base URL**: `http://{gateway}:8080/api/synapse/agent-tools`
- **경로 (Base 제외)**: `/cases/{caseId}`, `/documents`, `/documents/{bukrs}/{belnr}/{gjahr}` 등 명세와 동일.

**전체 URL 예**
- `GET http://{gateway}:8080/api/synapse/agent-tools/cases/123`
- `GET http://{gateway}:8080/api/synapse/agent-tools/documents?bukrs=1000&gjahr=2024`
- `GET http://{gateway}:8080/api/synapse/agent-tools/documents/1000/12345/2024`
- `GET http://{gateway}:8080/api/synapse/agent-tools/lineage?caseId=123`

즉, Base가 `.../agent-tools`일 때 전체 URL은 `.../agent-tools/cases/123` 형태가 맞습니다.

---

## 5. web_search 도구

**확인해 두신 내용대로입니다.**

- **web_search**는 백엔드 agent-tools API를 타지 않습니다.
- config의 **tools[]**에 `toolName: "web_search"`가 포함되면, Aura는 **엔진 내부**에서 자체 구현(Tavily 등)만 바인딩하시면 됩니다.

---

## 요약 (Aura 쪽 반영 포인트)

| 항목 | 답변 |
|------|------|
| **(1) config 호출** | **GET /api/v1/agents/config?agent_key=finance_aura** (X-Tenant-ID 헤더 필수). agent_key만으로 설정 조회 가능. |
| **(2) version** | config 응답에 **version** (Integer, agent_prompt_history 현재 버전) 포함. |
| **(2) 도구 목록** | **tools[].toolName**만 사용. 이를 모아 agent_tool_mapping으로 사용 가능. |
| **(3) ApiResponse** | 실제 설정은 **data** 키. data 내부 필드명은 **camelCase** 유지. |
| **(4) agent-tools** | Base `.../agent-tools` + `/cases/{caseId}` 등 경로 맞음. |
| **(5) web_search** | BE agent-tools 아님. Aura 내부 구현만 사용. |

명세서 **AURA_AGENT_API_SPEC.md**에도 위 내용을 반영해 두었습니다.
