# Agent Studio — 에이전트 설정·관리 API 계약 (Cross-Check)

> **Aura 전달 API 규격 (양쪽 맞춤용)**: [AURA_AGENT_API_SPEC.md](AURA_AGENT_API_SPEC.md) — config API 경로·응답 필드·agent-tools 매핑을 한 문서로 정리. BE·Aura 양쪽이 이 명세를 기준으로 맞춥니다.

## 1. API

### 설정·조립 (Aura 런타임)

- **GET /api/v1/agents/{id}/config** (Gateway 경로) = **GET /api/synapse/agents/{id}/config**
  - **목적**: Aura 엔진이 런타임에 에이전트를 조립할 때 사용
  - **헤더**: `X-Tenant-ID` 필수
  - **응답**: `agentId`, `agentKey`, `name`, `domain`, `model`, `systemInstruction`, `tools`

### CRUD·카탈로그 (FE 관리)

- **POST /api/synapse/agents** — 신규 에이전트 생성 (agent_master + 초기 prompt_history, 도구 매핑 선택)
- **PUT /api/synapse/agents/{id}** — 에이전트 수정 (모델/파라미터/프롬프트/도구 매핑). 프롬프트 변경 시 버전 이력 유지(`is_current` 전환)
- **GET /api/synapse/agents/tools** — Aura에 등록 가능한 전체 도구 인벤토리 (FE '도구' 탭)
- **GET /api/synapse/agents/knowledge** — 업로드된 RAG 문서 리스트 및 상태 (FE '지식' 탭). `X-Tenant-ID` 필수, 쿼리: `page`, `size`
- **GET /api/synapse/agents/catalog** — domains, docTypes, models(app_codes), tools 한꺼번에. 도구는 `schemaJson` 포함(비어있지 않으면 FE에서 도구별 설정 동적 렌더링용).

## 2. 코드 검증 및 삭제 정책

- **코드 검증**: `domain`, `model_name`은 저장 시 `dwp_aura.app_codes`(활성 `is_active=true`)에 존재해야 함. 미존재 시 **400 Bad Request**, 메시지: **"정의되지 않은 시스템 코드입니다."**
- **프롬프트 버전**: 수정 시 `agent_prompt_history`에 새 레코드 추가 + 기존 `is_current` 해제는 **동일 트랜잭션 내 원자적** 처리.
- **에이전트 삭제 (의사결정)**: 연관 `agent_tool_mapping`, `agent_prompt_history`는 **Soft Delete가 아닌 Cascade Delete**(DB FK ON DELETE CASCADE 또는 삭제 절차상 일괄 삭제)로 처리함. FE는 삭제 시 복구 불가임을 인지.

## 3. 에이전트 ID 규칙 (Agent Key)

- **계약**: `agent_key`는 Snake Case 권장 (예: `finance_aura`, `hr_aura`). Aura 호출 시 이 값을 **Key**로 사용하기로 합의.
- **규칙**: 소문자, 숫자, 언더스코어만 허용. tenant 내 unique. 생성 시 검증(`CreateAgentRequest.agentKey`).

## 4. 도구 명칭·구조적 청킹·샌드박스

- **도구 명칭**: DB `tool_name`과 Aura `@tool` 함수명 일치(예: `web_search` 통일). 상세: [AGENT_STUDIO_NAMING_AND_SANDBOX_CONTRACT.md](AGENT_STUDIO_NAMING_AND_SANDBOX_CONTRACT.md).
- **구조적 청킹**: 계층형 문서 업로드 시 조/항 인식 1건 이상 로그 교차 검증. BE는 RAG 청크 저장 시 첫 청크 메타데이터/텍스트 prefix를 로그로 출력.
- **샌드박스**: `X-Sandbox: true` 시 Thought Chain DB 저장 생략(임시 세션). 동일 문서 참조.

## 5. Tool Name 규격 (Cross-Check)

- **계약**: Aura 엔진의 `FINANCE_TOOLS`에 등록된 **함수명**과 `dwp_aura.agent_tool_inventory.tool_name`이 **100% 일치**해야 합니다.
- **이유**: config API가 반환하는 `tools[].toolName`을 Aura가 그대로 호출할 때, 해당 이름의 도구가 엔진에 없으면 런타임 오류가 발생합니다.
- **권장**:
  - `agent_tool_inventory` 시드/관리는 Aura 측 `FINANCE_TOOLS` 정의와 동기화하여 진행
  - 신규 도구 추가 시: Aura에 함수 등록 후, 동일한 문자열을 `tool_name`으로 DB에 등록

## 6. Tenant Isolation

- **에이전트**: `agent_master.tenant_id`로 격리. `GET /api/v1/agents/{id}/config` 호출 시 `X-Tenant-ID`와 일치하는 에이전트만 반환합니다.
- **지식 베이스**: `knowledge_base_master.tenant_id`로 격리.
- **도구 카탈로그**: `agent_tool_inventory`는 전역(tenant 없음). 에이전트별 사용 도구는 `agent_tool_mapping`으로 매핑되며, 에이전트가 tenant 소유이므로 간접적으로 격리됩니다.

## 7. 에러 코드 (FE 전달)

| 코드 | HTTP | 설명 |
|------|------|------|
| E3007 | 400 | AGENT_CREATE_FAILED — 에이전트 생성 실패 |
| E3008 | 409 | AGENT_KEY_DUPLICATE — 이미 사용 중인 agent_key |
| E4004 | 400 | PROMPT_VALIDATION_ERROR — 프롬프트 검증 실패 (system_instruction 확인) |
| E4003 | 400 | INVALID_CODE — 정의되지 않은 시스템 코드 (domain/model_name 등 app_codes 미존재) |
| E3000 | 404 | ENTITY_NOT_FOUND — 에이전트 없음 또는 비활성 |

## 8. DB 테이블 요약

| 테이블 | tenant_id | 비고 |
|--------|-----------|------|
| agent_master | 필수 | 격리 |
| agent_prompt_history | 없음 (agent_id FK로 간접) | |
| agent_tool_inventory | 없음 | 전역 카탈로그, tool_name unique |
| agent_tool_mapping | 없음 | agent_id로 간접 격리 |
| knowledge_base_master | 필수 | 격리 |
