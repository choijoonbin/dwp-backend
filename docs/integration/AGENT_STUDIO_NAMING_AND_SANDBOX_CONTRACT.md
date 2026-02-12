# 에이전트 스튜디오 — 도구 명칭·구조적 청킹·샌드박스 계약

FE/Aura/BE 간 합의 사항을 문서화합니다.

---

## 1. 도구 명칭 일치 (Naming)

### 계약

- **백엔드 DB** `dwp_aura.agent_tool_inventory.tool_name`과 **Aura 코드의 `@tool` 데코레이터 함수명**이 **반드시 동일**해야 합니다.
- 표시용 라벨(예: "Google Search")과 DB/Aura 함수명(예: `web_search`)을 구분하며, **호출 키는 함수명 하나로 통일**합니다.

### 규칙

| 구분 | 사용 값 | 비고 |
|------|---------|------|
| DB `tool_name` | `web_search` (Snake Case) | Aura가 호출할 이름 |
| Aura `@tool` 함수명 | `web_search` | 위와 100% 동일 |
| FE 표시명 | "Google Search" 등 자유 | UI 전용, API/호출과 무관 |

### Cross-Check

- 신규 도구 추가 시: Aura에 `@tool` 함수 등록 후 **동일 문자열**을 `agent_tool_inventory.tool_name`에 INSERT.
- `GET /api/synapse/agents/tools` 응답의 `toolName`을 Aura가 그대로 호출하므로, 불일치 시 런타임 오류 발생.

---

## 2. 구조적 청킹 프리뷰 (Hierarchical)

### 계약

- FE에서 문서를 **계층형(Hierarchical)**으로 업로드한 경우, Aura가 **조(條)·항(項) 번호**를 인식하여 청크 메타데이터 또는 본문에 포함해야 합니다.
- 백엔드는 **1건 이상** 청크 수신 시 로그로 교차 검증할 수 있도록, 계층형 문서의 첫 청크(또는 대표 1건) 메타데이터/요약을 로그에 남깁니다.

### Aura 측 기대

- 청크 콜백 시 `metadata`에 구조 정보 포함 권장 예:
  - `article_number` (조 번호)
  - `section_number` 또는 `item_number` (항 번호)
  - 또는 `content` 내에 "제1조", "①" 등으로 포함
- BE는 수신 청크의 `metadata_json`(및 필요 시 `chunk_text` 앞부분)을 **계층형 문서일 때** 로그로 1건 이상 출력하여, FE/Aura와 조·항 인식 여부를 교차 검증할 수 있게 합니다.

### BE 구현

- RAG 청크 저장 시, 해당 문서가 계층형으로 분류된 경우(또는 `metadata`에 조/항 필드가 있는 경우) **대표 1건**을 `INFO` 로그로 출력 (doc_id, chunk_index, metadata_json 요약 또는 chunk_text prefix). 이를 통해 "계층형 업로드 → Aura 조·항 인식"이 1건 이상 로그로 확인 가능합니다.

---

## 3. 테스트 채팅의 신뢰성 — 샌드박스 임시 세션 (Sandbox)

### 계약

- **샌드박스(테스트) 채팅**에서 나온 대화 내용은 **DB에 영구 저장하지 않습니다** (임시 세션 처리).
- FE와 BE(및 Aura) 협의: 샌드박스 여부를 구분할 수 있는 방법을 두고, 샌드박스일 때는 대화/사고 로그를 DB에 쓰지 않습니다.

### 구분 방법 (협의·구현)

| 제안 | 설명 |
|------|------|
| **헤더** | `X-Sandbox: true` — **BE 구현 완료**. 분석 스트림 `GET /api/synapse/analysis-runs/{runId}/stream` 호출 시 이 헤더를 보내면 Thought Chain 로그를 DB에 저장하지 않음. (`HeaderConstants.X_SANDBOX`) |
| **세션 타입** | Aura/FE가 세션 생성 시 `sessionType=sandbox` 등으로 전달하는 방식은 향후 확장 시 사용 가능. |

### BE 측 동작 (구현됨)

- **분석 스트림**: `GET /api/synapse/analysis-runs/{runId}/stream` 요청에 **`X-Sandbox: true`** 가 있으면 `ThoughtChainLogService.saveLog(..., sandbox=true)` 호출 시 **DB 저장 생략**.
- **chat-service**: 향후 채팅 메시지 영구 저장 시, 동일하게 `X-Sandbox`(또는 합의한 헤더)를 확인하여 **샌드박스면 DB 저장 생략**하도록 구현합니다.

### FE/Aura 협의

- FE: 테스트용 채팅 UI 진입 시 `X-Sandbox: true`(또는 합의한 헤더/파라미터)를 설정하여 요청.
- Aura: 동일 플래그를 백엔드로 전달하고, 샌드박스 세션에서는 대화 저장을 요청하지 않음.

---

## 4. 요약

| 항목 | 계약 | 비고 |
|------|------|------|
| 도구 명칭 | DB `tool_name` = Aura `@tool` 함수명 (Snake Case 통일) | 표시명은 FE만 사용 |
| 구조적 청킹 | 계층형 문서 시 조·항 인식 결과를 1건 이상 로그로 교차 검증 | BE: 저장 시 대표 1건 로그 |
| 샌드박스 | 샌드박스 세션은 DB 영구 저장 금지 (임시 세션) | 헤더/세션 타입으로 구분, BE persistence 스킵 |
