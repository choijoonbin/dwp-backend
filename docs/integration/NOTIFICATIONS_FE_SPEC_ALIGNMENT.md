# 알림(Notifications) FE 스펙 정합 — front.txt 기준

> **프론트 알람 관련 작업 내용 공유** (front.txt) 기준으로 백엔드 구현과의 정합 여부 정리.  
> 일자: 2026-02-11

---

## 1. FE 요청 스펙 요약 (front.txt)

- **엔드포인트**: `ws://localhost:8080/ws/notifications` (Gateway 8080 기준)
- **실제 WS**: SynapseX(8085) — **STOMP over SockJS**
- **Gateway**: `Path=/ws/**` → SynapseX
- **구독 토픽**: `/topic/notifications`
- **메시지**: JSON **camelCase** — id, type, title, content, **link**, occurredAt, createdAt, readAt, tenantId, userId, channel, payload
- **link 규칙**: payload.link 있으면 사용, 없으면 case_id → `/synapse/cases/{id}`, docId → `/synapse/rag/documents/{id}` 자동 생성
- **type 예시**: CASE_ACTION, RAG_STATUS, AI_DETECT, TRAINING_COMPLETE, APPROVAL_COMPLETE, GENERIC
- **REST**: GET 목록, PATCH `/{id}` 단건 읽음, PATCH `/read-all` 전체 읽음 (`?userId=` 선택)

---

## 2. 백엔드 구현 정합 여부

| front.txt 항목 | 백엔드 구현 | 비고 |
|----------------|-------------|------|
| ws://localhost:8080/ws/notifications | ✅ | Gateway `/ws/**` → SynapseX, SynapseX `/ws/notifications` |
| STOMP over SockJS, /topic/notifications | ✅ | WebSocketConfig |
| id, type, title, content, link, occurredAt, createdAt, readAt, tenantId, userId, channel, payload | ✅ | NotificationDto, @JsonProperty로 camelCase 보장 |
| link: payload.link / case_id / docId 자동 생성 | ✅ | NotificationBroadcastService, NotificationQueryService — toDto 시 link 설정 |
| GET /api/synapse/notifications (X-Tenant-ID) | ✅ | NotificationController |
| PATCH /api/synapse/notifications/{id} | ✅ | markAsRead |
| PATCH /api/synapse/notifications/read-all?userId= | ✅ | markAllAsRead, NotificationReadAllResultDto.markedCount |

**정합**: front.txt에 적힌 WebSocket 경로·메시지 형식(link 포함)·REST(목록·읽음/전체 읽음) 모두 현재 백엔드 구현과 일치합니다.

---

## 3. 타입 차이 (참고)

- **id, tenantId, userId**: FE 문서에는 string으로 적혀 있을 수 있음. 백엔드는 **number**(Long)로 직렬화. JSON에서는 number로 오므로 FE에서 number → string 변환만 하면 됨.
- **occurredAt, createdAt, readAt**: 백엔드는 ISO-8601 **string**으로 직렬화 (Instant → Jackson 기본).

---

## 4. 참고 문서

| 문서 | 내용 |
|------|------|
| **front.txt** (FE 공유) | 상단 알림 연동 스펙 요청·백엔드 검증 체크리스트 |
| NOTIFICATIONS_BACKEND_VERIFICATION.md | 백엔드 검증 상세·구현 위치 |
| NOTIFICATIONS_HEADER_SPEC_REQUEST.md | 요청 스펙 요약 (BE 확인용) |
| NOTIFICATION_WEBSOCKET_SPEC.md | Redis 구독·WebSocket·설정 |

프론트 알람 관련 작업 내용(front.txt)과 백엔드는 위와 같이 맞춰져 있습니다.
