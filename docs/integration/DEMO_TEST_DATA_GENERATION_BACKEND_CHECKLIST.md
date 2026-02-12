# 테스트 데이터 생성 프로세스 — 백엔드 점검 체크리스트

시연 제어(데모) 메뉴에서 테스트 데이터 생성 시, **백엔드에 해당되는 2~6단계**가 명세대로 구현되었는지 정리한 문서입니다.

---

## 1단계: [프론트] 테스트 데이터 생성 요청

- 사용자가 시연 제어 메뉴에서 시나리오·강도 선택 후 '생성' 클릭.
- **백엔드**: 해당 없음 (FE 담당).

---

## 2단계: [백엔드] 가상 SAP 데이터 생성 및 1차 탐지

| 항목 | 내용 | 구현 여부 |
|------|------|------------|
| 전표 저장 | `fi_doc` 등 가상 SAP 데이터를 DB에 저장 | ✅ `DemoViolationService` 등에서 전표 생성·저장 |
| 비동기 Detect | 저장 직후 비동기 Detect 엔진으로 위반 의심 건 탐지 | ✅ `DemoDetectTrigger` 등에서 Detect 배치 트리거 |
| 케이스 생성 | 위반 의심 건을 **agent_case**로 생성 | ✅ Detect 결과로 `agent_case` 생성 |
| CASE_ACTION 알림 | 케이스 생성 즉시 프론트로 알림 → 상단 토스트 | ✅ `workbench:case:action`에 `case_created` 발행, Notification → WebSocket `/topic/notifications` |

**관련 코드**: `DemoViolationService`, `DemoDetectTrigger`, `DetectBatchService`, `NotificationRedisSubscriber`, `WorkbenchActionCompletionPublisher`

---

## 3단계: [백엔드 → Aura] AI 분석 트리거 (자동 호출)

| 항목 | 내용 | 구현 여부 |
|------|------|------------|
| 자동 트리거 | 사용자 버튼 없이 Aura 분석 API 즉시 호출 | ✅ `AnalysisAutoTriggerService.triggerAnalysisForCase()` (케이스당 1회, 비동기) |
| 전표·규정 맥락 전달 | 전표 내용 + 적용 규정집 맥락을 Aura에 전달 | ✅ `CaseAnalysisService.triggerAnalysis()` → evidence(전표/lineage 등), 규정집은 Aura가 RAG로 조회 |

**관련 코드**: `AnalysisAutoTriggerService`, `CaseAnalysisService.triggerAnalysis()`, `AuraCaseTabClient`, `workbench.analysis-trigger-delay-ms`(600ms 지연 후 분석 시작)

---

## 4단계: [Aura → 백엔드] 실시간 사고 체인(Thought Chain) 전송

| 항목 | 내용 | 구현 여부 |
|------|------|------------|
| Aura 처리 | search_documents(RAG) 등으로 규정집 검색·전표 대조 추론 | ✅ Aura 측 구현 (백엔드는 RAG 콜백 수신) |
| 스트리밍 수신 | 분석 도중 '생각(Thought)'을 백엔드로 스트리밍 | ✅ `AnalysisStreamProxyService` — Aura SSE 수신 |
| THOUGHT_STREAM 이벤트 | 수신 시마다 프론트로 thought_stream 발행 (타자 효과) | ✅ `workbench:case:action`에 `thought_stream` 발행, payload에 `data` 포함 |
| 사고 과정 DB 저장 | 시연 후 근거 조회를 위해 Thought Chain 로그 저장 | ✅ `thought_chain_log` 테이블 + `ThoughtChainLogService.saveLog()`, 스트림 수신 시 저장 |

**관련 코드**: `AnalysisStreamProxyService.streamFromAura()`, `publishThoughtStreamIfApplicable()`, `ThoughtChainLogService`, `ThoughtChainLog` / `ThoughtChainLogRepository`

---

## 5단계: [Aura → 백엔드] 최종 분석 결과 확정

| 항목 | 내용 | 구현 여부 |
|------|------|------------|
| 최종 결과 전달 | 위반 조항, 위험 점수, 판단 근거, 액션 제안 등 | ✅ Aura 콜백 → `CaseAnalysisService.handleAuraCallback()` |
| 콜백 처리 | status=COMPLETED 시 결과·제안 저장 | ✅ `saveResultAndProposals()` / `saveResultAndProposalsFromPhase3()` |

**관련 코드**: `CaseAnalysisService.handleAuraCallback()`, `AuraCallbackPayload`, `CaseAnalysisResult`, `CaseActionProposal`

---

## 6단계: [백엔드] 최종 데이터 인서트 및 상태 갱신

| 항목 | 내용 | 구현 여부 |
|------|------|------------|
| agent_case 갱신 | 위반 등급(severity)·상태·판단 근거·점수 등 분석 결과 반영 | ✅ `updateAgentCaseFromLatestResult()` — COMPLETED 시 `case_analysis_result` 기준으로 `agent_case` 갱신 (severity, reasonText, score, ragRefsJson, updatedAt) |
| case_analysis_result | Aura 최종 리포트 저장 | ✅ `saveResultAndProposals()` 등에서 저장 |
| action_proposal | AI 제안 조치(소명 요청 등) 저장 | ✅ `saveProposalsFromItems()` — `case_action_proposal` 저장 |
| thought_chain_log | AI 사고 과정 로그 저장 (시연 후 근거 조회) | ✅ 4단계 스트림 수신 시 `ThoughtChainLogService.saveLog()` 로 저장, `thought_chain_log` 테이블 (V51) |

**관련 코드**: `CaseAnalysisService.handleAuraCallback()` → `updateAgentCaseFromLatestResult()`, `saveResultAndProposals()`, `saveProposalsFromItems()`, `ThoughtChainLogService`, `ThoughtChainLogRepository.findByRunIdOrderByCreatedAtAsc()`

---

## 요약

- **2단계**: fi_doc 저장 → 비동기 Detect → agent_case 생성 → case_created 알림 ✅  
- **3단계**: Aura 분석 자동 트리거(전표·맥락 전달) ✅  
- **4단계**: Thought Chain 스트리밍 수신 → thought_stream 발행 + thought_chain_log 저장 ✅  
- **5단계**: Aura 최종 결과 콜백 수신 ✅  
- **6단계**: agent_case 갱신, case_analysis_result·action_proposal·thought_chain_log 저장 ✅  

위 프로세스에 따른 백엔드 기능이 모두 반영되어 있습니다.  
추가로 **thought_chain_log 조회 API**(runId 기준 목록)가 필요하면 Query 서비스·컨트롤러에 노출하면 됩니다.
