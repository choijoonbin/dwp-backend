# Phase3 FE·Aura 공유 대응 — BE 답변 및 질문

> FE(front.txt)·Aura(aura.txt) 공유 내용 검토 후 BE 작업 반영 및 전달사항·확인 요청 정리.

---

## 1. FE 전달사항에 대한 BE 답변

### 1.1 확인 요청 항목 (FE → BE)

| 항목 | BE 상태 | 비고 |
|------|--------|------|
| **GET analysis 응답에 ragRefs** | ✅ 제공 | `data.ragRefs`는 case_analysis_result.rag_refs_json을 그대로 반환. Aura 콜백에서 전달한 구조(refId, sourceType, sourceKey, excerpt, score 등)가 저장·반환됨. Aura가 해당 필드로 보내면 FE에서 그대로 렌더 가능. |
| **GET action-proposals 응답에 fingerprint** | ✅ 제공 | 각 항목에 **fingerprint** 필드 포함(dedup_key와 동일). FE dedup 적용 가능. |
| **decision API** | ✅ 제공 | `POST /api/synapse/cases/{caseId}/action-proposals/{proposalId}/decision`  
  Body: `{ "decision": "APPROVE" \| "REJECT", "comment"?: string }`  
  기존 approve/reject와 동일 동작. **제공 완료** → FE에서 이 API로 전환 가능. |
| **execute API** | ✅ 제공 | **두 가지 경로 모두 지원.**  
  - `POST /api/synapse/cases/{caseId}/actions/execute`  
    Body: `{ "proposalId": UUID, "runId"?: UUID, "mode"?: "SIMULATION" }`  
    (FE 요청 스펙에 맞춤. **caseId는 path, proposalId는 body 필수.**)  
  - `POST /api/synapse/cases/{caseId}/action-proposals/{proposalId}/execute`  
    (path로 proposalId 지정, body 없음)  
  → "실행(시뮬)" 버튼에서 위 두 API 중 편한 쪽 사용 가능. |

### 1.2 Run 목록·최신 run (FE 3.3)

- **run 목록**: `GET /api/synapse/cases/{caseId}/analysis-runs` (latest 생략 시) → `[{ runId, status, startedAt }, ...]` 배열 반환.
- **최신 runId만**: `GET /api/synapse/cases/{caseId}/analysis-runs?latest=true` → `{ runId }` 단일 객체.
- **과거 run 조회**: `GET .../analysis?runId=`, `GET .../action-proposals?runId=` 에 **과거 runId** 넣어 호출 시 해당 run 기준 결과 반환 가능.  
→ Run 선택 UI(최신/이전 N개) 구현 시 위 API로 충족 가능.

### 1.3 200/202, streamUrl, completed 후 refetch, fingerprint dedup

- BE는 **POST analysis-runs 항상 202 Accepted** 반환. FE 200/202 모두 성공 처리 정책이면 그대로 호환.
- streamUrl은 Aura Phase3 사용 시 **streamPath**를 그대로 내려줌 (상대 경로 예: `/aura/cases/{caseId}/analysis/stream?runId=...`). 절대 URL 필요 시 Gateway/Aura 배포 URL 정책에 따라 BE 또는 Gateway에서 prefix 보정 가능.
- completed 후 refetch, fingerprint 기준 중복 1건 표시: BE 동작과 FE 전달사항 일치.

---

## 2. Aura 전달사항에 대한 BE 반영

### 2.1 Phase3 콜백 스키마 수용 ✅

- **콜백 엔드포인트**: 기존 `POST /api/synapse/internal/aura/callback` 유지.
- **수신 스키마**: **Phase2(finalResult)** 와 **Phase3(analysis + proposals + meta)** **둘 다** 수용.
  - Phase3: `runId`, `caseId`, `status`, `analysis` (score, severity, reasonText, evidence, ragRefs), `proposals[]`, `meta`, 실패 시 `error`.
  - 수신 시 `analysis != null` 이면 analysis+proposals 기준으로 저장, 아니면 기존처럼 `finalResult` 기준 저장.
- **Phase3 전용 콜백 URL**: 동일 경로 사용. Aura가 트리거 시 받은 **resultCallbackUrl**에 위 URL을 BE가 넣어 주면 됨.

### 2.2 Phase3 트리거 연동 (BE → Aura) ✅

- **설정 시** Phase3 트리거 사용:
  - **URL**: `POST /aura/internal/cases/{caseId}/analysis-runs`
  - **Body**: runId, caseId, requestedBy, **artifacts**(BE 입력 패키지 = document/openItems/evidence/lineage 등), **callbacks** (resultCallbackUrl, auth), options.
- **설정 방법**:  
  - `aura.phase3.callback-base-url`: Aura가 콜백 호출할 **BE 콜백 URL 전체** (예: `https://gateway.../api/synapse/internal/aura/callback`).  
  - `aura.phase3.callback-auth-token`: (선택) Aura가 콜백 시 사용할 Bearer 토큰.  
  - 위 두 값이 설정되어 있으면 BE는 Phase3 트리거를 사용하고, **Authorization 헤더 필수** (Phase3 호출 시 반드시 전달 필요).
- **미설정 시**: 기존 Phase2 트리거(`POST /aura/cases/{caseId}/analysis-runs`) 사용.

---

## 3. BE → FE·Aura 확인 요청 (질문)

### 3.1 FE 측

- **execute body 스펙**: FE 요청은 `body: { runId, proposalId, mode }` 였고, BE는 **caseId는 path**, **body는 { proposalId 필수, runId?, mode? }** 로 제공했습니다.  
  → **path에 caseId, body에 proposalId** 로 통일해 두었습니다. runId/mode는 선택이며, 필요 시 추후 확장용. 이 스펙으로 FE 연동 가능한지 확인 부탁드립니다.

- **ragRefs 필드명**: GET analysis의 ragRefs는 Aura 콜백에서 오는 구조를 그대로 저장·반환합니다. FE 기대 필드(refId, sourceType, sourceKey, excerpt, score)는 **Aura가 동일 이름으로 콜백에 포함**해 주시면 그대로 사용 가능합니다. Aura 스키마와 필드명 일치 여부만 확인이 필요합니다.

### 3.2 Aura 측

- **트리거 URL**: 현재 BE는  
  - Phase3 사용 시: `POST /aura/internal/cases/{caseId}/analysis-runs`  
  - Phase2 사용 시: `POST /aura/cases/{caseId}/analysis-runs`  
  로 호출합니다. Aura에서 **internal** 경로가 실제로 노출되어 있는지, 및 **Authorization 필수** 정책이 맞는지 확인 부탁드립니다.

- **콜백 인증**: BE가 `callbacks.auth.token`을 넣어 주면 Aura가 콜백 시 `Authorization: Bearer {token}`으로 호출하는 구조로 이해했습니다. 토큰 발급 주체(예: BE에서 고정 시크릿 발급 vs Gateway JWT)에 대한 정책이 정해지면 BE에서 그에 맞춰 설정하겠습니다.

- **streamPath 반환값**: Phase3 202 응답의 **streamPath**가 상대 경로(예: `/aura/cases/{caseId}/analysis/stream?runId=...`)인지, 절대 URL인지 알려주시면 FE에 내려줄 때 prefix 정책을 맞추겠습니다.

### 3.3 CORS / 스트림 연결 (제품 결정 사항)

- FE 공유의 **옵션 A(FE가 Aura로 직접 SSE)** vs **옵션 B(BE 프록시)** 에 대해, BE는 현재 **streamUrl/streamPath를 그대로 내려주는 방식**입니다.  
  - Aura 절대 URL 사용 시: Aura 또는 Gateway에서 해당 Origin에 대한 CORS/SSE 허용이 필요합니다.  
  - BE 프록시로 통일할 경우: streamUrl을 BE 경로로 통일하는 별도 작업이 필요합니다.  
  → **어느 쪽으로 통일할지** 제품/인프라 결정 후 알려주시면 BE에서 그에 맞춰 정리하겠습니다.

---

## 4. 작업 요약 (BE 수행 완료)

| 구분 | 내용 |
|------|------|
| **Phase3 콜백** | analysis + proposals + meta 수신·저장, FAILED 시 error 메시지 저장 |
| **Phase3 트리거** | aura.phase3.callback-base-url 설정 시 /aura/internal/.../analysis-runs + artifacts + callbacks 호출 |
| **FE decision API** | POST .../action-proposals/{proposalId}/decision, body { decision, comment } |
| **FE execute API** | POST .../cases/{caseId}/actions/execute, body { proposalId, runId?, mode? } |
| **기존 API 유지** | approve/reject, path 기반 execute 그대로 사용 가능 |

위 반영 사항과 확인 요청(§3)까지 협의 후, 추가 이슈가 있으면 본 문서나 handoff 채널에 보완해 두겠습니다.

---

## 5. FE 답변 반영 (계약 확정)

### 5.1 FE 반영 완료 (FE → BE 공유)

| 출처 | 내용 | FE 반영 |
|------|------|--------|
| **BE** | fingerprint, decidedBy, decidedAt, decisionComment | DTO·UI 반영, 결정 메타 표시 |
| **BE** | approve/reject body `{ "comment": "..." }` | API·mutation에 comment 옵션 추가 |
| **BE** | `POST .../action-proposals/{proposalId}/execute` (APPROVED만) | execute API·useExecuteProposalMutation, "실행(시뮬)" 버튼(APPROVED 시) |
| **Aura** | streamPath (스트림 경로) | 응답에서 streamUrl 또는 streamPath 사용, 상대 경로 시 NX_API_URL 접두 |
| **Aura** | 이벤트 started/step/agent/completed/failed | agent 처리·completed/[DONE] 처리 이미 반영 |

### 5.2 계약 정리 (FE·BE 합의)

- **스트림**: BE가 Aura 트리거 후 받은 **streamPath**를 FE에 전달. FE는 `streamUrl` 또는 `streamPath`로 수신해 `GET /aura/cases/{caseId}/analysis/stream?runId=` 에 연결(상대 경로면 NX_API_URL 접두).
- **결정**: BE는 approve/reject 유지, body에 `comment` 선택.
- **실행**: `POST .../action-proposals/{proposalId}/execute`, body 없음. APPROVED 제안만 호출 가능.

→ FE는 path 기반 execute API 사용. BE는 동일 API 유지하며, body 기반 `POST .../actions/execute`는 선택용으로 계속 제공.

### 5.3 BE → FE 전달사항 체크리스트 (FE 문서 반영)

BE에서 전달한 4가지 항목별 FE 반영 여부 (FE 측 정리).

| BE 전달 항목 | FE 반영 상태 |
|--------------|--------------|
| 응답 스키마 fingerprint, decidedBy, decidedAt, decisionComment | ✅ DTO 추가, 액션제안 탭에서 결정 메타 표시 |
| 승인·거절 body `{ "comment": "..." }` | ✅ comment 옵션 전달, decision API로 호출 |
| 실행(시뮬) POST .../execute, APPROVED만, executionId·executedAt | ✅ execute 연동, APPROVED 시 버튼, 성공 시 toast (executedAt 카드 표시는 선택) |
| 중복 표시 fingerprint, run별 grouping, 1건 표시, 상태·감사 반영 | ✅ fingerprint dedup(최신 1건), refetch로 상태 반영 |

**요약**: BE 전달 4항목 모두 FE에 반영 완료. 실행 완료 시 카드에 executedAt 등을 추가 표시하는 것은 선택 사항으로 FE 문서에 표기됨.

---

## 6. Aura 답변 반영 (계약 확정)

### 6.1 Aura 반영 완료 (Aura → BE 공유)

- **Phase2 body.evidence 확장 수용**: document, openItems, partyIds, lineage, policies 를 보내면 **evidence_items**로 정규화해 파이프라인에 반영. evidence, ragRefs 는 기존처럼 유지.

### 6.2 Aura 확인 요청에 대한 BE 답변

| Aura 확인 요청 | BE 상태 |
|----------------|--------|
| **트리거 응답 202**: Phase2/Phase3 모두 202 Accepted 반환. **202를 실패로 처리하지 않도록** 확인 | ✅ **이미 반영됨.** BE는 Feign 202 수신 시 `failRunWithMessage` 호출하지 않고, run을 STARTED로 유지한 뒤 콜백 대기. 202를 실패로 처리하지 않습니다. |
| **스트림**: 종료 시 event: completed 보낸 뒤 data: [DONE] 전송. FE completed 후 refetch 형태 | ✅ Aura 측 구현 완료. BE는 스트림 내용을 변경하지 않으며, FE가 해당 형태로 refetch하는 계약과 일치합니다. |

→ 트리거 202·스트림 종료 계약 모두 BE·Aura·FE 간 합의 상태입니다.

### 6.3 Aura 확인 요청 (Phase3 trigger·콜백)에 대한 BE 답변

Aura 측에서 “Phase3 internal trigger 호출·도입 일정 및 Phase3 전용 콜백(resultCallbackUrl) 제공 여부” 확인을 요청한 항목에 대한 BE 답변입니다.

| Aura 확인 요청 | BE 답변 |
|----------------|--------|
| **Phase3 internal trigger** (`POST /aura/internal/cases/{caseId}/analysis-runs`) **호출·도입 일정** | ✅ **이미 구현·도입 완료.** BE는 **설정 기반**으로 Phase3 트리거를 사용합니다. `aura.phase3.callback-base-url` 이 설정되어 있으면 **Phase2 트리거 대신** `POST /aura/internal/cases/{caseId}/analysis-runs` 를 호출하며, body에 runId, caseId, requestedBy, **artifacts**(evidence 확장 패키지), **callbacks**(resultCallbackUrl, auth), options 를 담아 전달합니다. **일정**: 코드 반영 완료. 운영 반영 시점은 배포·설정(아래 callback-base-url) 적용 시점입니다. |
| **Phase3 전용 콜백 엔드포인트(resultCallbackUrl) 제공 여부** | ✅ **제공.** BE는 Phase2·Phase3 **동일 콜백 URL**을 사용합니다. `POST /api/synapse/internal/aura/callback` 이 그 주소이며, Phase3 트리거 사용 시 이 URL을 **callbacks.resultCallbackUrl** 에 넣어 Aura에 전달합니다. 해당 엔드포인트는 Phase2(finalResult)와 Phase3(analysis+proposals+meta) **둘 다** 수신·저장하므로, “Phase3 전용” 별도 URL이 아닌 **공용 콜백 URL**로 Phase3 resultCallbackUrl을 제공하는 형태입니다. |

**운영 시 Phase3 트리거 사용 조건**:  
- `aura.phase3.callback-base-url`: Aura가 콜백 호출할 **전체 URL** (예: `https://{gateway}/api/synapse/internal/aura/callback`)  
- (선택) `aura.phase3.callback-auth-token`: 콜백 시 Aura가 사용할 Bearer 토큰  
위 두 값 중 **callback-base-url**이 설정되면 BE는 해당 run부터 Phase3 internal trigger + resultCallbackUrl 전달을 사용합니다.

**※ Aura 측 "Phase3 internal·콜백 도입 계획 BE 명시적 회신" 요청에 대한 답변**:  
위 §6.3이 그 **BE 명시적 회신**입니다. 요약하면, **BE는 `POST /aura/internal/cases/{caseId}/analysis-runs` 를 쓸 예정**이며(설정 시), **Phase3 전용 콜백 URL(resultCallbackUrl)을 제공**합니다(동일 엔드포인트 `POST /api/synapse/internal/aura/callback`). 도입 일정은 코드 반영 완료, 운영 반영은 배포·callback-base-url 설정 적용 시점입니다.

**Aura 최종 확인 (BE 문서 수신)**  
Phase3 internal 호출·Phase3 전용 콜백 제공 여부/일정에 대한 답은 **BE 문서 §6.3**과 **Aura handoff §16**으로 확정. **Aura는 BE 쪽에 추가 질문 없음.** Phase3 연동에 필요한 확인은 위 회신으로 모두 마무리된 상태로 Aura 측에 기록됨.

---

## 7. 미답변·추가 확인 필요 (BE 기준)

아래는 §3에서 BE가 확인 요청했으나 **아직 명시적 답변을 받지 못했거나**, **제품/인프라 결정이 필요한** 항목입니다.

### 7.1 FE

| 항목 | BE 확인 요청 내용 | 상태 |
|------|------------------|------|
| **ragRefs 필드명** | GET analysis의 ragRefs는 Aura 콜백 구조를 그대로 반환. FE 기대 필드(refId, sourceType, sourceKey, excerpt, score)를 **Aura가 콜백에 동일 이름으로 포함**해 주면 사용 가능. Aura 스키마와 필드명 일치 여부 확인 필요. | ✅ **Aura 답변**: **Phase3** `analysis.ragRefs` 는 **refId, sourceType, sourceKey, excerpt, score** 로 전송·스키마 고정. FE 기대 필드명과 동일. BE는 저장·반환 시 같은 필드명 쓰면 됨. **Phase2** `finalResult.ragRefs` 는 evidence 유래 구조라 위와 다를 수 있음. FE는 GET analysis 시 해당 필드 있는 항목만 ragRefs로 표시, Phase2 동일 스키마 필요 시 추후 맞추면 됨. |

※ **execute body**: FE는 path 기반 execute 사용으로 정리됨. **추가 확인 불필요** (FE 답변 반영).

### 7.2 Aura (✅ Aura 답변 수신)

| 항목 | BE 확인 요청 내용 | 상태 / Aura 답변 |
|------|------------------|------------------|
| **트리거 URL** | Phase3 시 `POST /aura/internal/cases/{caseId}/analysis-runs` 호출. Aura에서 **internal** 경로 노출 여부, **Authorization 필수** 정책 일치 여부. | ✅ **답변**: 해당 경로 노출 중. `Authorization: Bearer <token>` 은 전역 정책으로 **필수** (Phase2와 동일). |
| **콜백 인증** | BE가 `callbacks.auth.token`을 넣어 주면 Aura가 콜백 시 `Authorization: Bearer {token}` 사용. 토큰 발급 주체(고정 시크릿 vs Gateway JWT 등) 정책. | ✅ **답변**: `callbacks.auth.token` 을 그대로 `Authorization: Bearer {token}` 으로 사용. 토큰 발급 주체·형식은 **BE/게이트웨이 정책**에 따르면 되며, Aura는 전달만 함. |
| **streamPath 형식** | Phase3 202 응답의 **streamPath**가 상대 경로인지 절대 URL인지. FE에 내려줄 때 prefix 정책 맞추기 위함. | ✅ **답변**: **상대 경로**로 반환. `/aura/cases/{caseId}/analysis/stream?runId={runId}`. FE에 절대 URL로 내려줄 때는 **BE(Gateway) base URL**을 prefix 하면 됨. |
| **ragRefs 콜백 스키마** | FE 기대 필드(refId, sourceType, sourceKey, excerpt, score)로 Aura가 보내줄지, BE 저장·반환 시 동일 필드명 사용 가능한지. | ✅ **답변**: **Phase3** `analysis.ragRefs` 는 refId, sourceType, sourceKey, excerpt, score 로 전송·스키마 고정. BE는 저장·반환 시 같은 필드명 사용. **Phase2** `finalResult.ragRefs` 는 evidence 유래로 구조 다를 수 있음. FE는 해당 필드 있는 항목만 ragRefs 표시, Phase2 동일 스키마 필요 시 추후 맞춤. |

### 7.3 제품 / 인프라 (✅ 결정 반영)

| 항목 | 내용 | 상태 / 결정 |
|------|------|-------------|
| **스트림 연결 주체** | **옵션 A**: FE가 Aura 절대 URL로 직접 SSE. **옵션 B**: BE가 streamUrl을 BE 프록시 경로로만 내려주고 FE는 BE로만 SSE, BE가 Aura SSE 서버사이드 중계. | ✅ **옵션 B(권장, 운영 기본)**. FE는 항상 **BE SSE 프록시**로만 연결. CORS/인증/토큰/망분리 이슈 최소화, Aura는 내부망 유지 가능. **옵션 A**(직접 Aura URL)는 **개발/로컬 디버깅용 feature flag로만 허용, 운영 OFF.** |

**BE 측 정리 사항 (옵션 B)**  
- **운영 기본**: 트리거 응답의 **streamUrl**을 **BE 프록시 경로**로 내려줌 (예: `/api/synapse/analysis-runs/{runId}/stream` 또는 Gateway 경로). 해당 엔드포인트에서 Aura SSE를 **서버사이드로 중계**.  
- **개발/로컬**: feature flag로 streamUrl을 Aura 직접 URL로 내려주는 **옵션 A** 허용 가능. 운영 환경에서는 flag OFF.

---

**요약**: FE 전달 4항목·계약 답변 완료. execute body 추가 확인 불필요. **Aura §7.2 답변 완료**(트리거 URL·콜백 인증·streamPath·**ragRefs 스키마**). **ragRefs**: Phase3 analysis.ragRefs는 refId/sourceType/sourceKey/excerpt/score 고정, BE 저장·반환 시 동일 필드명. Phase2 finalResult.ragRefs는 evidence 유래로 다를 수 있음. **스트림 연결 주체 §7.3**: 옵션 B(운영 기본), 옵션 A는 dev/로컬용 flag. **미확정 없음.**

---

## 8. 시스템별 추가 확인·미답변 현황 (한눈에 보기)

| 시스템 | 추가 확인 필요 / 미답변 | 비고 |
|--------|-------------------------|------|
| **FE** | 없음 | 전달 4항목·계약·execute body·ragRefs 기대 필드 명시 완료. streamPath/completed/event:agent·Q3 답변 반영 완료. **옵션 B(프록시 streamUrl)** 방향성 정상. FE: step 라벨·failed error 객체·체크리스트 표시 반영 완료. |
| **Aura** | 없음 | 트리거 URL·콜백 인증·streamPath·**ragRefs 스키마** 답변 완료. Phase3 analysis.ragRefs = refId, sourceType, sourceKey, excerpt, score 고정. |
| **제품/인프라** | 없음 | 스트림 연결 주체 옵션 B(운영 기본) 결정 반영. |

---

## 9. FE·인프라 확인사항 — BE·Gateway 답변

FE/인프라 측에서 Phase3 연동 완료를 위해 확인한 항목에 대한 **BE·Gateway 측 답변**입니다.

| # | 구분 | FE/인프라 확인 내용 | BE·Gateway 답변 |
|---|------|---------------------|-----------------|
| 1 | Phase3 연동 완료 조건 | **이 항목만 확정되면 Phase3 연동에 필요한 확인은 모두 완료** (ragRefs 콜백 스키마 등) | ✅ **확정 완료.** BE·FE·Aura·스트림 결정·**ragRefs 스키마**(Aura 답변: Phase3 refId/sourceType/sourceKey/excerpt/score 고정) 반영 완료. Phase3 연동에 필요한 확인 모두 완료. |
| 2 | 인프라/Gateway | (선택) NX_API_URL + streamPath 연결 시, Gateway에서 `/aura/...` **라우팅·인증**을 Aura에 전달하는지 | **운영**은 옵션 B로 **BE SSE 프록시만** 사용하므로, FE는 NX_API_URL로 Gateway를 거쳐 **BE 프록시 경로**에만 연결. `/aura/...` 직접 라우팅은 **필수 확인 아님**. **개발/로컬**에서 옵션 A(직접 Aura URL) feature flag 사용 시에는, Gateway에 `/aura/...` 라우팅이 있으면 해당 경로로 Aura까지 요청·인증이 전달되도록 설정하면 됨(선택). |
