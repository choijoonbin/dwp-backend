# Redis 발행 메시지 최종 샘플 (Aura → Backend)

> **목적**: 백엔드 확정 규격에 맞춘 Aura Redis 발행 채널·페이로드 최종 대조 및 샘플  
> **일자**: 2026-02-11  
> **공유**: Aura 팀 규격서 반영, Backend 구독·매핑 정합성 유지  
> **참고**: Aura 팀 정리 문서(Redis 발행·채널·스키마·샘플·정합성 요약)와 동일 규격 — 질문 없음, 참고용 유지

---

## 1. 채널명 최종 대조

백엔드가 구독 중인 Redis 패턴과 Aura가 발행하는 채널명을 아래와 같이 일치시켰습니다.

| 용도 | 채널명 (문자열 그대로) | Aura 설정 키 | Aura 발행 위치 |
|------|------------------------|-------------|----------------|
| 고위험 탐지 (AI_DETECT) | `workbench:alert` | `workbench_alert_channel` | `core/analysis/phase2_pipeline.py` |
| RAG 학습 완료 (RAG_STATUS) | `workbench:rag:status` | `workbench_rag_status_channel` | `core/analysis/rag.py` |
| 조치 결과 통보 (CASE_ACTION) | `workbench:case:action` | `case_action_redis_channel` | `core/action_integrity/service.py` |

- **Backend 구독**: `NotificationRedisConfig`에서 패턴 `workbench:*`(PSUBSCRIBE) 사용 → 위 세 채널 모두 수신.
- **채널 상수**: Aura는 `core/notifications.py`에 `REDIS_CHANNEL_WORKBENCH_ALERT`, `REDIS_CHANNEL_WORKBENCH_RAG_STATUS`, `REDIS_CHANNEL_WORKBENCH_CASE_ACTION` 정의, 설정 미지정 시 fallback 사용.
- **config 기본값**: `core/config.py`의 해당 필드 기본값이 위 채널명과 동일.

---

## 2. 공통 메시지 스키마

모든 워크벤치 알림은 아래 공통 구조를 따릅니다. 백엔드 `NotificationRedisSubscriber`에서 기대하는 필드와 일치합니다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `type` | string | ✅ | 항상 `"NOTIFICATION"` |
| `category` | string | ✅ | `"AI_DETECT"` \| `"RAG_STATUS"` \| `"CASE_ACTION"` (대소문자 일치) |
| `message` | string | ✅ | 사용자/시스템용 메시지 |
| `timestamp` | string | ✅ | ISO 8601 UTC (예: `2026-02-11T14:30:00.123456+00:00`) |
| 기타 | — | 선택 | 이벤트별 추가 필드(평면 구조로 루트에 포함) |

- **인코딩**: UTF-8. `json.dumps(..., ensure_ascii=False)` 후 `payload.encode("utf-8")`로 발행.

**Backend 수신 시 매핑 (NotificationDto)**  
백엔드는 `workbench:*` 채널 구독 후 JSON 수신 시 아래처럼 매핑합니다. Aura는 해당 필드를 모두 포함해 발행합니다.

| Aura 페이로드 필드 | NotificationDto 필드 |
|--------------------|------------------------|
| `category` | `type` |
| `message` | `content` |
| `timestamp` | `occurredAt` |

→ DB 저장 후 `/topic/notifications` 브로드캐스트.

---

## 3. Redis 발행 메시지 최종 샘플

### 3.1 `workbench:alert` — AI_DETECT (고위험 탐지)

**발행 시점**: Phase2 분석에서 `severity == "HIGH"`인 케이스가 생성될 때.

```json
{
  "type": "NOTIFICATION",
  "category": "AI_DETECT",
  "message": "신규 이상 징후 탐지",
  "timestamp": "2026-02-11T14:40:00.123456+00:00",
  "case_id": "CASE-HIGH-001",
  "score": 0.85,
  "severity": "HIGH"
}
```

---

### 3.2 `workbench:rag:status` — RAG_STATUS (학습 완료)

**발행 시점**: RAG 벡터화(`process_and_vectorize_pgvector`) 성공 직후.

```json
{
  "type": "NOTIFICATION",
  "category": "RAG_STATUS",
  "message": "학습 완료",
  "timestamp": "2026-02-11T14:35:00.000000+00:00",
  "rag_document_id": "reg-v1.2",
  "chunks_added": 12
}
```

---

### 3.3 `workbench:case:action` — CASE_ACTION (조치 결과 통보)

**발행 시점**: HITL 승인/거절/타임아웃(보류) 시 `record_case_action()` 호출 후.

**승인 예시**

```json
{
  "type": "NOTIFICATION",
  "category": "CASE_ACTION",
  "message": "조치 승인됨",
  "timestamp": "2026-02-11T14:30:00.123456+00:00",
  "case_id": "DEMO00001",
  "request_id": "req-abc123",
  "executor_id": "user-456",
  "action_type": "APPROVE",
  "approved": true,
  "status_code": "APPROVED"
}
```

**거절 예시**

```json
{
  "type": "NOTIFICATION",
  "category": "CASE_ACTION",
  "message": "조치 거절됨",
  "timestamp": "2026-02-11T14:31:00.000000+00:00",
  "case_id": "DEMO00002",
  "request_id": "req-def456",
  "executor_id": "user-789",
  "action_type": "REJECT",
  "approved": false,
  "status_code": "REJECTED"
}
```

---

## 4. Backend 정합성 요약

| 항목 | 상태 |
|------|------|
| 채널명 `workbench:alert` | ✅ 백엔드 패턴 `workbench:*`로 수신 |
| 채널명 `workbench:rag:status` | ✅ 동일 |
| 채널명 `workbench:case:action` | ✅ 동일 |
| `category` → type | ✅ 모든 채널에서 페이로드 `category` 우선 사용 |
| `message` → content | ✅ 모든 채널에서 페이로드 `message` 우선 사용 |
| `timestamp` → occurredAt | ✅ 모든 채널에서 ISO8601 파싱 후 사용 |
| RAG 페이로드 `rag_document_id` | ✅ content fallback 시 `rag_document_id` 또는 `doc_id` 사용 |
| 메시지 인코딩 | ✅ UTF-8 수신 처리 |

`NotificationRedisSubscriber`: 페이로드에 `category`, `message`, `timestamp`가 있으면 그대로 type/content/occurredAt에 매핑하고, 없을 때만 채널별 fallback(제목·내용 생성) 적용.
