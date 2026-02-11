# Phase3 BE 마무리 — back.txt 스키마 반영 및 결과 정리

> back.txt 기준 API 스키마 정합성 반영 완료. 작업 완료 후 **각 시스템 확인 필요 사항**, **의사결정 필요 사항**, **질문**을 정리함.

---

## 1. 이번에 반영한 작업 (back.txt 스키마)

| 항목 | 반영 내용 |
|------|------------|
| **GET /cases/{caseId}/analysis** | 응답 `data`에 **runId** 추가. `CaseAnalysisDto.runId` (UUID). 정상 결과 반환 시 항상 포함. |
| **GET /cases/{caseId}/action-proposals** | 각 항목에 **updatedAt** 추가. `CaseActionProposalDto.updatedAt` (Instant). entity의 `updated_at` 매핑. |
| **POST /cases/{caseId}/actions/execute** 요청 | Body에 **gatewayRequestId** (optional, String) 추가. 추적용으로 수신만 하고, 현재 로직에서는 미사용. |
| **POST .../actions/execute 응답** | **actionId** (String, executionId와 동일 값), **simulation** (Map, result_json 기반) 추가. 기존 executionId, proposalId, status, mode, executedAt 유지. |

**참고**

- **streamUrl 정책**: back.txt에는 "반드시 Aura streamUrl"로 되어 있으나, **Handoff §7.3**에서 **옵션 B(운영 기본)**로 확정됨.  
  → **운영**: BE가 **streamUrl을 BE 프록시 경로**로 내려주고, FE는 BE로만 SSE 연결.  
  → **개발/로컬**: 옵션 A(직접 Aura URL)는 feature flag로만 허용.  
  따라서 back.txt의 "Aura streamUrl"은 **개발/로컬 또는 옵션 A 플래그 시**에만 해당하고, **운영 기본은 BE 프록시 URL**임을 문서로 명시.

- **감사 이벤트명**: **정책 의사결정 반영 완료.** BE 상수를 SoT로 문서와 일치시킴.  
  - `RUN_STARTED` / `RUN_COMPLETED` / `RUN_FAILED` (run 생성·콜백 완료/실패)  
  - `PROPOSAL_UPSERTED` (콜백 proposals upsert), `PROPOSAL_DECIDED` (decision API 성공)  
  - `ACTION_EXECUTED` / `ACTION_FAILED` (실행 성공/실패, 실패 시 반드시 기록)

---
## 1-2. 정책 의사결정 반영 (PHASE3_BE_POLICY_DECISIONS_AND_SSE_PROXY_PROMPT)

| 항목 | 반영 내용 |
|------|------------|
| **streamUrl** | **운영 기본**: 항상 `"/api/synapse/analysis-runs/{runId}/stream"` (BE 프록시). **개발/로컬**: `synapse.stream-url-use-aura-direct=true` 시 Aura 절대 URL 반환. |
| **BE SSE 프록시** | `GET /api/synapse/analysis-runs/{runId}/stream` — Aura 스트림을 server-side로 연결하여 그대로 중계. Query `caseId`(선택), Authorization 전파. 타임아웃 30분, 버퍼링 없이 전달. |
| **gatewayRequestId** | execute 요청 시 수신·저장(`case_action_execution.gateway_request_id`), 감사 로그 `gateway_request_id` 포함. 동일 ID 재요청 시 기존 결과 반환(멱등). |
| **실행 실패** | 상태 비승인/예외 시 proposal 상태 FAILED, `ACTION_FAILED` 감사 기록. |

---

## 2. 각 시스템에 확인이 필요한 사항

### 2.1 FE

| # | 확인 요청 | 비고 |
|---|------------|------|
| 1 | **GET analysis** 응답에 **runId**가 추가되었습니다. FE에서 runId를 표시·필터·캐시 키 등에 사용할 계획이 있다면 해당 필드 사용 가능합니다. | 선택 확인 |
| 2 | **GET action-proposals** 응답에 **updatedAt**이 추가되었습니다. 카드/목록에 "수정 시각" 표시가 필요하면 이 필드 사용 가능합니다. | 선택 확인 |
| 3 | **POST actions/execute** 응답에 **actionId**(문자열), **simulation**(객체)가 추가되었습니다. 실행 결과 상세/시뮬레이션 결과 표시 시 이 필드 사용 가능합니다. | 선택 확인 |
| 4 | **gatewayRequestId**: 요청 body optional. BE는 저장(execution)·감사 포함·멱등(동일 ID 재요청 시 기존 결과 반환) 반영 완료. FE에서 생성·전달 권장. | 확인 완료 |

### 2.2 Aura

| # | 확인 요청 | 비고 |
|---|------------|------|
| 1 | Phase3 콜백·트리거·ragRefs 스키마는 Handoff §6·§7 기준으로 확정된 상태입니다. **추가 확인 요청 없음.** | Handoff 반영 완료 |

### 2.3 Gateway / 인프라

| # | 확인 요청 | 비고 |
|---|------------|------|
| 1 | **옵션 B(운영 기본)** 적용 시, BE가 내려주는 **streamUrl**이 BE(Gateway) 프록시 경로일 때, 해당 경로의 **SSE 라우팅·타임아웃·버퍼링 비활성화** 설정이 적용되어 있는지 확인 필요. | Handoff §7.3, §9 #2 |

---

## 3. 의사결정 반영 완료 (정책 확정)

| # | 항목 | 상태 |
|---|------|------|
| 1 | **gatewayRequestId** | ✅ 저장(execution)·감사 포함·멱등 처리 반영 |
| 2 | **감사 이벤트명** | ✅ RUN_STARTED, RUN_COMPLETED, RUN_FAILED, PROPOSAL_UPSERTED, PROPOSAL_DECIDED, ACTION_EXECUTED, ACTION_FAILED 상수·사용 통일 |
| 3 | **ACTION_FAILED** | ✅ 실행 실패 시 proposal FAILED + ACTION_FAILED 감사 기록 |

---

## 4. 질문 (BE → FE·인프라)

| # | 대상 | 질문 |
|---|------|------|
| 1 | FE | GET analysis **runId**, action-proposals **updatedAt**, execute 응답 **actionId**·**simulation**을 UI/로직에 반영할 예정인지, 아니면 추후 확장용으로만 두어도 되는지? |
| 2 | 인프라 | **BE SSE 프록시** 구현 완료. Gateway/리버스프록시에서 `/api/synapse/analysis-runs/*/stream` SSE 친화 설정(timeout·keep-alive 확장, response buffering off) 확인. |

---

## 5. 참조 문서

- **Handoff**: `PHASE3_FE_AURA_HANDOFF_BE_RESPONSE.md` (streamUrl 옵션 B, ragRefs, §8 미확정 없음)
- **BE 주간 결과**: `PHASE3_BE_WEEK1_WEEK2_result.md`
- **지시서**: back.txt (Phase3 BE 구현 지시), work.txt (로드맵 참고)
- **정책 프롬프트**: `docs/job/PHASE3_BE_POLICY_DECISIONS_AND_SSE_PROXY_PROMPT.txt`

---

**작성일**: 2025-02-09  
**갱신**: 2026-02-10 — 정책 의사결정 반영(옵션 B SSE 프록시, streamUrl, gatewayRequestId, 감사 이벤트명, ACTION_FAILED).
