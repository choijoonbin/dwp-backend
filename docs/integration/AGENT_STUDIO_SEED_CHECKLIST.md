# Aura 에이전트 일괄 등록용 체크리스트

현재 사용 중인 Aura 에이전트를 `agent_master` / `agent_prompt_history` / `agent_tool_mapping`에 기본값으로 넣기 위해, **에이전트별로** 아래 정보를 준비하면 됩니다.

---

## 1. app_codes 사용 가능 코드값 (선택 시 아래만 사용)

에이전트 등록 시 **domain**, **model_name** 등은 반드시 아래 `app_codes`에 정의된 **code** 값만 사용해야 합니다. (API 검증 시 미정의 코드는 400 처리됩니다.)

### 1.1 AGENT_DOMAIN (에이전트 업무 영역) — 3개

| code (입력값) | name | 설명 |
|---------------|------|------|
| **FINANCE** | 재무 감사 | 재무/감사 영역 |
| **HR** | 인사 | 인사 영역 |
| **DEV** | 개발 | 개발/엔지니어링 영역 |

→ **domain** 필드: 위 3개 중 하나 선택. 미선택 시 null.

### 1.2 LLM_MODEL (사용 가능 엔진) — 3개

| code (입력값) | name | 설명 |
|---------------|------|------|
| **gpt-4o** | GPT-4o (High Speed) | OpenAI GPT-4o |
| **claude-3-5-sonnet** | Claude 3.5 Sonnet | Anthropic Claude 3.5 Sonnet |
| **r1** | R1 | Reasoning model |

→ **model_name** 필드: 위 3개 중 하나 선택. 미선택 시 null.

### 1.3 DOC_TYPE (문서 유형, 지식베이스/청킹용) — 4개

에이전트 마스터가 아닌 **지식베이스** 등록 시 사용합니다.

| code (입력값) | name | 설명 |
|---------------|------|------|
| **HIERARCHICAL** | 계층형(규정집) | 조/항 구조 |
| **SEQUENTIAL** | 순차형 | 순차 문서 |
| **POLICY** | 정책 | 정책 문서 |
| **GENERAL** | 일반 | 일반 문서 |

---

## 2. 에이전트 1건당 필요 정보

| 구분 | 필드 | 필수 | 설명 / 허용값 |
|------|------|------|----------------|
| **식별** | **agent_key** | ✅ | Aura에서 쓰는 키. snake_case, tenant 내 unique (예: `finance_aura`, `hr_aura`) |
| **식별** | **name** | ✅ | 표시명 (예: Finance 감사 에이전트) |
| **코드** | **domain** | ⭕ | **§1.1 AGENT_DOMAIN** 코드 3개 중 하나 (FINANCE / HR / DEV). 없으면 null |
| **코드** | **model_name** | ⭕ | **§1.2 LLM_MODEL** 코드 3개 중 하나 (gpt-4o / claude-3-5-sonnet / r1). 없으면 null |
| **모델** | **temperature** | ⭕ | 0~1 (기본 예: 0.2) |
| **모델** | **max_tokens** | ⭕ | 정수 (기본 예: 4096) |
| **프롬프트** | **system_instruction** | ✅ | 현재 Aura에서 사용 중인 system prompt **전문** (한 건당 1개, is_current=true로 등록) |
| **도구** | **tool_names** | ⭕ | 이 에이전트가 쓰는 도구 이름 목록. `agent_tool_inventory.tool_name`과 **완전 동일** (예: `get_case`, `get_documents`) |

- **tenant_id**: 시드 시 보통 `1`. 다른 테넌트면 에이전트별로 지정.

---

## 3. 사전에 필요한 것 (전역 1회)

| 항목 | 설명 |
|------|------|
| **agent_tool_inventory** | Aura에서 사용하는 **모든 도구**(19개: Finance 10 + Git 5 + GitHub 4)가 등록되어 있어야 함. `tool_name`은 Aura 함수명과 동일. **전체 목록·설명·파라미터·에이전트별 매핑**: docs/handoff/TOOL_INVENTORY_FOR_BACKEND.md, 명칭 계약: docs/handoff/TOOL_NAMING_FOR_BACKEND.md. |
| **app_codes** | **§1**에 나열한 AGENT_DOMAIN(3개), LLM_MODEL(3개), DOC_TYPE(4개)는 V54 시드로 들어가 있음. domain / model_name은 해당 코드값만 사용. |

---

## 4. 등록 시 채워지는 테이블

- **agent_master**: 에이전트 1건당 1행 (tenant_id, agent_key, name, domain, model_name, temperature, max_tokens, is_active).
- **agent_prompt_history**: 에이전트 1건당 1행 (agent_id, system_instruction, version=1, is_current=true).
- **agent_tool_mapping**: 에이전트별로 사용 도구 수만큼 행 (agent_id, tool_id). tool_id는 `agent_tool_inventory`에서 tool_name으로 조회.

---

## 5. 예시 (에이전트 1건)

```
agent_key          : finance_aura
name               : Finance 감사 에이전트
domain             : FINANCE
model_name         : gpt-4o
temperature        : 0.2
max_tokens         : 4096
system_instruction : (Aura에서 현재 사용 중인 프롬프트 전문)
tool_names         : [ get_case, get_documents, get_open_items, ... ]
```

이 형식으로 “지금 사용 중인 Aura 에이전트”만큼 채우면, 그걸 기준으로 V55 이후 시드 마이그레이션 또는 관리자 시드 스크립트에 넣으면 됩니다.
