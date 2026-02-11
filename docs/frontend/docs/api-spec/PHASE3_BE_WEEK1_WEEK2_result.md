# Phase3 BE Week1·Week2 작업 결과

> Phase3 DDL(동일 run 내 동일 제안 중복 방지) 및 2주 플랜 기준 BE 구현 완료 요약.

---

## 1. Week1 — 입력 패키지 표준화 ✅

- **대상**: `POST /api/synapse/cases/{caseId}/analysis-runs` 트리거 시 Aura로 전달하는 evidence 스냅샷.
- **변경**: `CaseAnalysisService.buildEvidenceSnapshot()` 확장.
  - 기존: `evidence`, `ragRefs` 만 전달.
  - 추가: **document**(header + items, DOCUMENT/OPEN_ITEM 동일 구조), **openItems**(해당 doc key 기준 fi_open_item 목록), **partyIds**(relatedPartyIds), **lineage**(LineageQueryService 결과), **policies**(빈 배열).
- **document 없음 케이스**: open-item 기반 증적도 `document` 필드에 동일 구조(header + items)로 매핑하여 전달.
- **의존성**: `CaseQueryService`, `LineageQueryService`, `FiOpenItemRepository` 주입 후 케이스 상세·오픈아이템·lineage 조회하여 JSON 구성.

---

## 2. Proposal API — fingerprint·결정 메타 ✅

- **fingerprint**: `CaseActionProposalDto`에 **fingerprint** 필드 추가. 값은 DB `dedup_key`와 동일. GET analysis·GET action-proposals 응답에 포함.
- **decided_by, decided_at, decision_comment**:
  - DB: `V36__case_action_proposal_decided_columns.sql`로 컬럼 추가.
  - 엔티티 `CaseActionProposal` 및 DTO `CaseActionProposalDto`에 반영.
  - GET analysis / GET action-proposals 응답에 **decidedBy**, **decidedAt**, **decisionComment** 포함.

---

## 3. POST decision (APPROVE/REJECT) + comment ✅

- **엔드포인트**: 기존  
  `POST .../action-proposals/{proposalId}/approve`,  
  `POST .../action-proposals/{proposalId}/reject`  
  유지.
- **Body(선택)**: `ProposalDecisionRequest` — `{ "comment": "..." }` 지원.
- **동작**: 승인/거절 시 `decided_by`(userId), `decided_at`(now), `decision_comment`(body.comment) 저장. 감사 이벤트 afterJson에 comment 포함.

---

## 4. case_action_execution + 실행(시뮬) API ✅

- **테이블**: `V37__case_action_execution.sql`  
  - execution_id, tenant_id, case_id, run_id, proposal_id, mode, status, result_json, error_message, executed_by, executed_at, created_at.
- **엔티티**: `CaseActionExecution` (dwp_aura.case_action_execution).
- **API**:  
  `POST /api/synapse/cases/{caseId}/action-proposals/{proposalId}/execute`
  - **조건**: 해당 proposal이 **APPROVED**일 때만 실행 가능.
  - **동작**:  
    - `case_action_execution`에 1건 저장 (mode=SIMULATION, status=COMPLETED, result_json에 simulated·proposalType 등).  
    - proposal 상태를 **EXECUTED**로 변경.  
    - 감사 이벤트 **ACTION_EXECUTED** 기록.
  - **응답**: `ProposalExecuteResponseDto` (executionId, proposalId, status, mode, executedAt).

---

## 5. 마이그레이션·파일 요약

| 구분 | 파일 |
|------|------|
| Migration | V36__case_action_proposal_decided_columns.sql, V37__case_action_execution.sql |
| Entity | CaseActionProposal(decidedBy, decidedAt, decisionComment), CaseActionExecution(신규) |
| Repository | CaseActionExecutionRepository(신규) |
| DTO | CaseActionProposalDto(fingerprint, decidedBy, decidedAt, decisionComment), ProposalDecisionRequest, ProposalExecuteResponseDto |
| Service | CaseAnalysisService(buildEvidenceSnapshot 확장, approve/reject comment·decided 필드 반영, executeProposal) |
| Controller | CaseAnalysisController(approve/reject body, POST .../execute) |

---

## 6. 시스템별 전달사항

### FE (Frontend)

- **응답 스키마 변경**
  - GET analysis·GET action-proposals의 proposal 항목에 **fingerprint**, **decidedBy**, **decidedAt**, **decisionComment** 가 추가되었습니다. 타입/UI 반영 필요.
- **승인·거절 호출**
  - POST approve/reject 시 선택 body `{ "comment": "..." }` 지원. 코멘트 입력 시 해당 필드로 전달하면 됩니다.
- **실행(시뮬) 플로우**
  - `POST .../action-proposals/{proposalId}/execute` 신규. **APPROVED** 상태 제안만 호출 가능하며, 호출 후 proposal 상태가 EXECUTED로 바뀝니다. 응답의 executionId·executedAt 등으로 실행 완료 UI 표시 가능.
- **중복 표시**
  - 동일 run 내 동일 제안은 **fingerprint**가 같습니다. FE 체크리스트(Week2): run별 grouping, fingerprint 기준 중복 1건 표시, 승인/거절/실행 클릭 시 상태·감사 반영.

### Aura (Python / FastAPI)

- **트리거 시 evidence 페이로드 확장**
  - BE가 `POST /aura/cases/{caseId}/analysis-runs` 호출 시 보내는 body.evidence 에 다음 필드가 추가됩니다.  
    **document** (header, items, type, docKey), **openItems** (배열), **partyIds** (배열), **lineage** (LineageResponseDto 구조), **policies** (빈 배열).  
    기존 **evidence**, **ragRefs** 는 그대로 유지됩니다.
- **document 구조**
  - document 없음 케이스도 open-item 기반으로 동일 구조(document 형태)로 채워 전달합니다. RAG/LLM 입력으로 document·openItems·lineage·partyIds 를 일관되게 사용할 수 있습니다.
- **콜백·스트림**
  - 기존 계약 유지. 실패 시 callback FAILED 반환, proposal dedup_key(fingerprint)는 BE가 계산·저장하므로 Aura는 proposals 전달만 하면 됩니다.

### Gateway

- **변경 없음.**  
  분석·제안·실행 관련 경로는 기존 라우팅 그대로 사용하면 됩니다.

### BE (Synapsex) 운영

- **마이그레이션**
  - V36(case_action_proposal decided 컬럼), V37(case_action_execution 테이블) 적용 후 서비스 기동 필요.
- **감사 이벤트**
  - ACTION_EXECUTED 가 실행(시뮬) 시 기록됩니다. 모니터링/검색 시 참고.

---

## 7. 계약 요약 (FE 참고)

- **GET /api/synapse/cases/{caseId}/analysis?runId=**  
  proposals 항목에 **fingerprint**, **decidedBy**, **decidedAt**, **decisionComment** 포함.
- **GET /api/synapse/cases/{caseId}/action-proposals?runId=**  
  동일 필드 포함.
- **POST .../approve**, **POST .../reject**  
  선택 body: `{ "comment": "..." }`.
- **POST .../action-proposals/{proposalId}/execute**  
  승인된 제안만 호출 가능. 응답: executionId, proposalId, status, mode, executedAt.

이제 동일 run 내 동일 제안 중복 방지(fingerprint/dedup_key), 결정 메타·실행(시뮬)·감사(ACTION_EXECUTED)까지 Phase3 2주 플랜 BE 항목이 반영된 상태입니다.  
각 시스템 전달사항은 위 **§6 시스템별 전달사항**을 참고하면 됩니다.

---

## 8. FE·Aura 공유 대응 (추가 반영)

- **FE 요청**: decision 단일 API, execute body 스펙 → **§7 계약** 및 **`PHASE3_FE_AURA_HANDOFF_BE_RESPONSE.md`** 에 상세 답변·질문 정리.
- **Aura 요청**: Phase3 콜백(analysis+proposals+meta), Phase3 트리거(callbacks/artifacts) → **콜백 스키마 둘 다 수용**, **phase3 callback URL 설정 시 Phase3 트리거 사용** 반영 완료.
- 상세 답변·확인 요청 사항: `docs/frontend/docs/api-spec/PHASE3_FE_AURA_HANDOFF_BE_RESPONSE.md` 참고.
- **FE 답변**: FE 반영 완료(fingerprint·결정 메타·comment·execute·streamPath·이벤트) 및 계약 정리(스트림/결정/실행) 공유됨. **§5 FE 답변 반영** 에 합의된 계약 기록. BE 전달 4항목 체크리스트(§5.3) 추가·전부 ✅ 반영 완료(executedAt 카드 표시는 선택).
- **Aura 답변**: evidence 확장(document/openItems/partyIds/lineage/policies → evidence_items) 수용, 202 실패 미처리·스트림 completed+[DONE] 확인 요청 → BE는 202를 이미 실패로 처리하지 않음. **§6 Aura 답변 반영** 에 기록.
