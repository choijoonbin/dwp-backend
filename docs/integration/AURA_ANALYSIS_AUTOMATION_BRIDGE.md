# Aura Analysis Automation Bridge

전표 데이터 생성부터 Aura Thought Chain 분석 완료까지 사용자 개입 없이 이뤄지는 엔드투엔드 자동화.

**Aura 전달 문서(aura.txt) 정합성**  
- §1 트리거·202·알림·스트림: 현재 명세와 일치.  
- §2 **finalResult 신규 4필드**: 콜백 수신 시 `case_analysis_result`에 저장, `CaseAnalysisDto`·`WorkbenchAnalysisResultDto`로 FE 노출.  
  - `risk_score` (0~100), `violation_clause`, `reasoning_summary`, `recommended_action`  
- §3 SSE step 이벤트: BE는 Aura 스트림을 그대로 프록시하므로, FE에서 step의 `detail`을 Thought Chain 문구로 표시하면 됨.  
- §4 GET analysis 응답: BE 캐시(위 4필드 포함)를 FE에 전달.  
- §5 정리: 추가 계약 변경 없음.  
- **§6 추가 확인사항 (연동 검증용)**  
  | # | 항목 | BE 현재 상태 |
  |---|------|----------------|
  | 1 | stream_url | `streamUrlUseAuraDirect=false`(기본) 시 FE에 **BE 프록시 경로** `/api/synapse/analysis-runs/{runId}/stream` 전달 ✅ |
  | 2 | 콜백 200 | `POST .../internal/aura/callback` → `ApiResponse.success(null)` → **200 OK** 반환 ✅ |
  | 3 | DEMO_OFF | Aura가 200+`status:disabled` 반환 시 `CaseAnalysisService`에서 run FAILED 처리 ✅ |
  | 4 | FAILED 콜백 | `status: FAILED` 시 finalResult 미가정, `error` 필드 정규화해 `error_message` 저장 ✅. *선택*: `partialEvents`에서 실패 사유 추출·저장/노출 가능 |
  | 5 | Authorization | 트리거 시 전달한 Authorization을 Aura 호출에 그대로 전달 ✅ |

## 흐름

1. **전표 생성** — `POST /api/demo/generate` 또는 `POST /api/synapse/demo/generate-violation`
2. **Detect (비동기)** — `DemoDetectTrigger`가 윈도우 내 전표로 `agent_case` 생성/갱신
3. **case_created 발행** — Redis `workbench:case:action` → 알림 DB 저장 → WebSocket `/topic/notifications`
4. **Aura 분석 자동 트리거** — 케이스별로 **비동기** `AnalysisAutoTriggerService.triggerAnalysisForCase()` → `AuraCaseTabClient.triggerAnalyze()` (API 응답 지연 없음)
5. **analysis_started 발행** — 동일 채널로 `run_id`, `stream_url`, `case_id` 포함 → WebSocket으로 프론트에 실시간 전달
6. **thought_stream 발행** — SSE 프록시(`GET .../stream`)에서 Aura의 `event: thought` / `event: step` 수신 시 동일 채널로 실시간 브리핑용 발행

## workbench:case:action 이벤트 규격 (FE 수신용)

| type | 용도 | 주요 payload 필드 |
|------|------|-------------------|
| **case_created** | 전표·케이스 생성 직후 (상단 알림) | `case_id`, `tenant_id`, `title`, `message`, `at` |
| **analysis_started** | Aura 분석 시작 (리스트 상태 업데이트) | `case_id`, `run_id`, `stream_url`, `tenant_id`, `at` |
| **thought_stream** | Thought Chain 중간 사고 (실시간 브리핑) | `case_id`, `run_id`, `tenant_id`, `event`(thought/step), `data`, `at` |

채널은 동일: Redis `workbench:case:action` → 구독 후 WebSocket `/topic/notifications` 로 전달. FE는 `payload.type` 또는 `payload.category`로 구분.

## 프론트 연동

- **자동 분석 동작 조건**: 요청 시 **Authorization**, **X-User-ID** 헤더 전달. (게이트웨이 경유 시 보통 전파됨)
- **분석 진행 상태**: WebSocket `/topic/notifications` 구독. 알림 `type`(category)이 `ANALYSIS_STARTED`이고 `payload.run_id`, `payload.stream_url`, `payload.case_id`로 SSE 스트림 연결 가능.
- **SSE 스트림**: `GET /api/synapse/analysis-runs/{runId}/stream` 로 Thought Chain 실시간 수신.

## 시나리오 생성기 연동 확인

- `generate-violation` 호출 시 위 1~5 단계가 순서대로 실행됨.
- Aura 미기동 또는 Authorization 미전달 시 해당 케이스만 `Aura auto-trigger failed` 로그로 실패하며, 나머지 케이스는 계속 처리됨.
