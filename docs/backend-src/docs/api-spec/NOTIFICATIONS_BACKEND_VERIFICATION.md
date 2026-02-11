# 실시간 알림 백엔드 검증 (WebSocket + REST)

> localhost:8080(또는 실제 API 호스트)에서 WebSocket 서버·경로·메시지 형식·REST가 스펙에 맞게 구현되었는지 확인한 결과.

---

## 1. WebSocket 서버·경로

| 확인 항목 | 상태 | 비고 |
|-----------|------|------|
| WebSocket 서버 구동 | ✅ | SynapseX(8085)에서 STOMP over SockJS 제공 |
| Gateway 경유 | ✅ | `Path=/ws/**` → SynapseX로 프록시 (dwp-gateway application.yml) |
| **엔드포인트** | ✅ | **`/ws/notifications`** (고정) |
| 실제 연결 URL (개발) | ✅ | `http://localhost:8080/ws/notifications` (Gateway 8080 기준, SockJS가 WS 업그레이드 협상) |
| 구독 토픽 | ✅ | **`/topic/notifications`** — 클라이언트 `subscribe('/topic/notifications', callback)` |

- **설정**: `notification.websocket.allowed-origins` (기본 `*`).  
- **문서**: `docs/integration/NOTIFICATION_WEBSOCKET_SPEC.md`.

---

## 2. 메시지 형식 (JSON) — back.txt 4.3 / 실시간 알림 스펙

**브로드캐스트 payload** = `NotificationDto` 직렬화 (camelCase).

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | number | sys_notifications PK |
| `tenantId` | number | 테넌트 ID |
| `userId` | number \| null | null = 전체 브로드캐스트 |
| `title` | string | 알림 제목 |
| `content` | string | 본문 요약 |
| `type` | string | **category** (아래 표) |
| `channel` | string | Redis 채널 (예: workbench:case:action) |
| **`link`** | string \| null | 딥링크 (예: /synapse/cases/3, /synapse/rag/documents/1). payload.link 또는 case_id/docId 기반 자동 생성 |
| `occurredAt` | string | ISO-8601 (이벤트 발생 시각) |
| `createdAt` | string | ISO-8601 (저장 시각) |
| `readAt` | string \| null | 읽음 시각 |
| `payload` | object \| null | 원본 Redis payload |

**type (category) 예시**: `CASE_ACTION`, `RAG_STATUS`, `AI_DETECT`, `TRAINING_COMPLETE`, `APPROVAL_COMPLETE`, `GENERIC` 등 (Redis 채널·이벤트에 따라 설정).

---

## 3. REST API (선택)

| 메서드 | 경로 | 설명 | 헤더 |
|--------|------|------|------|
| **GET** | `/api/synapse/notifications` | 알림 목록 (페이징, 최신순) | X-Tenant-ID 필수 |
| **PATCH** | `/api/synapse/notifications/{id}` | 단건 읽음 처리 | X-Tenant-ID 필수 |
| **PATCH** | `/api/synapse/notifications/read-all` | 전체 읽음 (선택: `?userId=`) | X-Tenant-ID 필수 |

- Gateway: `/api/synapse/notifications/**` → SynapseX (StripPrefix=1 → `/synapse/notifications/**`).
- **read-all** 응답: `{ "markedCount": number }`.

---

## 4. 구현 위치 요약

| 구분 | 파일/위치 |
|------|-----------|
| WebSocket 엔드포인트 | synapsex: `WebSocketConfig` — `/ws/notifications`, `/topic/notifications` |
| Gateway 라우팅 | dwp-gateway: `application.yml` — `/ws/**`, `/api/synapse/notifications/**` |
| 브로드캐스트 | synapsex: `NotificationBroadcastService` — Redis 수신 → DB 저장 → `SimpMessagingTemplate.convertAndSend("/topic/notifications", dto)` |
| DTO | synapsex: `NotificationDto` (id, type, title, content, link, occurredAt, createdAt, readAt, payload) |
| REST 컨트롤러 | synapsex: `NotificationController` — GET, PATCH /{id}, PATCH /read-all |

---

## 5. 체크리스트 (백엔드 담당자)

- [ ] localhost:8080(또는 실제 호스트)에서 `/ws/notifications` 접속 가능한지
- [ ] STOMP 구독 `/topic/notifications` 시 메시지 수신되는지
- [ ] 수신 JSON에 `id`, `type`, `title`, `content`, `link`, `occurredAt`, `createdAt` 포함되는지
- [ ] GET `/api/synapse/notifications` (X-Tenant-ID) 로 목록 조회되는지
- [ ] PATCH `/api/synapse/notifications/{id}` 로 읽음 처리되는지
- [ ] PATCH `/api/synapse/notifications/read-all` 로 전체 읽음 처리되는지

위가 모두 만족하면 실시간 알림 스펙(back.txt 4.3, NOTIFICATIONS_HEADER_SPEC_REQUEST.md)에 맞게 구현된 것으로 확인 가능.
