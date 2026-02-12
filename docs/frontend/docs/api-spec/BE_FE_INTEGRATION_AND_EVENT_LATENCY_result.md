# Backend–Frontend 연동 및 이벤트 발행 간격 공유 (BE → FE)

프론트엔드 연동·Aura 콜백·이벤트 타이밍에 대한 백엔드 보완 결과입니다.

---

## 1. RAG Chunk Callback URL (Aura 환경설정)

- **엔드포인트**: `POST /api/synapse/rag/chunks`  
- **보안**: Aura가 청크를 보낼 수 있도록 **완전 개방**되어 있습니다.
  - Gateway에서 **X-Tenant-ID 필수 검증 예외** 처리 (PermitAll에 준하는 동작).
  - API Key 미사용. tenant는 body의 `rag_document_id`로 문서 조회 후 자동 결정.
- **Aura .env 설정 (필수)**  
  벡터화 완료 시 아래 URL로 POST하도록 한 줄 설정하면 됩니다.

```bash
# 로컬 (Gateway 경유)
BACKEND_RAG_CHUNKS_SAVE_URL=http://localhost:8080/api/synapse/rag/chunks

# 배포 시
# BACKEND_RAG_CHUNKS_SAVE_URL=https://<gateway-host>/api/synapse/rag/chunks
```

상세 명세: `docs/integration/AURA_RAG_CHUNKS_CALLBACK_SPEC.md`

---

## 2. 이벤트 발행 간격 (Event Latency)

- **목적**: `case_created` 수신 후 리스트가 렌더링되기 전에 `analysis_started`가 먼저 도달하는 것을 막고, 흐름을 안정화합니다.
- **구현**: `case_created` 발행 직후, **analysis 트리거를 지연**한 뒤 실행합니다.
  - **기본 지연**: **600ms** (0.5초~1초 권장 구간).
  - 설정: `workbench.analysis-trigger-delay-ms` (환경변수: `WORKBENCH_ANALYSIS_TRIGGER_DELAY_MS`).
- **FE 기대 동작**  
  - `case_created` 수신 → 리스트에 신규 케이스 표시  
  - 약 **0.6초 후** `analysis_started` 수신 → 해당 케이스 상태를 “분석 중” 등으로 업데이트  
  - 필요 시 지연을 500~1000ms 범위로 조정 가능(백엔드 설정 변경).

---

## 3. SSE Proxy → thought_stream payload (ThoughtChainUI)

- **채널**: Redis `workbench:case:action` → WebSocket `/topic/notifications` 로 전달.
- **thought_stream** 발행 시 **`data` 필드**:
  - Aura SSE의 **"data:" 라인 내용을 그대로** 포함합니다.
  - 복수 라인이면 `\n`으로 결합합니다.
  - **텍스트 청크(Delta) 누락 없이** 전달되므로, ThoughtChainUI에서 `payload.data`를 파싱해 즉시 렌더링할 수 있습니다.
- **payload 예시**  
  `type`: `"thought_stream"`, `category`: `"THOUGHT_STREAM"`, `case_id`, `run_id`, `tenant_id`, `event`(thought/step), **`data`**(Aura가 보낸 data 라인 전체), `at`.

---

## 4. 요약

| 항목 | 내용 |
|------|------|
| **RAG 콜백** | `POST /api/synapse/rag/chunks` 개방. Aura에 `BACKEND_RAG_CHUNKS_SAVE_URL` 설정만 하면 됨. |
| **이벤트 간격** | case_created → analysis_started 사이 **기본 600ms** 지연. `workbench.analysis-trigger-delay-ms`로 조정 가능. |
| **thought_stream data** | Aura SSE data 라인 전체가 `data` 필드에 포함되어 ThoughtChainUI에서 delta 누락 없이 렌더링 가능. |
