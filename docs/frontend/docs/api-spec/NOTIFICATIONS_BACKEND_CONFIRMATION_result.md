# 상단 알림(Notifications) 연동 — 백엔드 확인 결과

FE 확인 요청(§8)에 대한 백엔드 스펙 확인 결과입니다.

---

## 8.1 WebSocket 연결 방식

| 확인 항목 | 백엔드 상태 | 비고 |
|-----------|-------------|------|
| **Raw WebSocket vs SockJS** | **SockJS 사용 필요** | SynapseX `WebSocketConfig`에서 `StompEndpointRegistry.addEndpoint("/ws/notifications").setAllowedOriginPatterns(...).withSockJS()` 로 등록되어 있어, **SockJS 엔드포인트만** 노출됩니다. Raw WebSocket만으로는 STOMP 핸드셰이크가 되지 않을 수 있으므로, **FE는 SockJS 클라이언트 + STOMP** 로 연결하는 것을 권장합니다. (예: `sockjs-client` + `@stomp/stompjs` 에서 SockJS를 transport로 사용) |
| **CORS / Origin** | **허용** | `notification.websocket.allowed-origins` 기본값 `*` 이며, `setAllowedOriginPatterns` 로 적용됩니다. 개발 시 `localhost:5173` → `ws://localhost:8080` 연결 시 Origin 검사로 거절하지 않습니다. |

---

## 8.2 STOMP 메시지 페이로드

| 확인 항목 | 백엔드 상태 | 비고 |
|-----------|-------------|------|
| **JSON 필드명** | **camelCase** | `NotificationDto` 는 Jackson 직렬화 시 **camelCase** (id, type, title, content, link, tenantId, userId, channel, occurredAt, createdAt, readAt, payload). FE에서 content(본문), type(카테고리), link(클릭 시 이동 URL) 사용 가능. **id, tenantId, userId** 는 Java Long → JSON **number** 로 나갑니다. (FE 스펙에 "id: string"으로 되어 있어도 숫자로 수신되면 문자열로 변환해 사용하면 됩니다.) |
| **type 값 목록** | 아래 표 참고 | Redis payload의 `category` 또는 채널별 fallback으로 설정됩니다. |
| **link 필드** | **BE에서 채움** | `NotificationBroadcastService.toDto()` 에서: **payload.link** 가 있으면 그대로 사용, 없으면 **payload.case_id** → `/synapse/cases/{id}`, **payload.docId** → `/synapse/rag/documents/{id}` 로 생성합니다. FE에서 별도 경로 조합 불필요. |

**type(category) 실제 사용 값** (브로드캐스트 가능 목록):

| type | 발생 경로 |
|------|-----------|
| **CASE_ACTION** | workbench:case:action (케이스 생성/조치 완료 등). 채널 fallback. |
| **ANALYSIS_STARTED** | 데모 Generate 후 Aura 분석 트리거 시 Redis payload `category: "ANALYSIS_STARTED"` 로 발행. |
| **RAG_STATUS** | workbench:rag:status. 채널 fallback. |
| **AI_DETECT** | Aura가 workbench:alert 등으로 `category: "AI_DETECT"` 발행 시. |
| **GENERIC** | workbench:* 기타 채널, category 없을 때 fallback. |
| **UNKNOWN** | DB 저장 시 type이 null일 때 서비스에서 넣는 값. |

---

## 8.3 테넌트/사용자 필터

| 확인 항목 | 백엔드 상태 | 비고 |
|-----------|-------------|------|
| **브로드캐스트 범위** | **전역** | `/topic/notifications` 는 **단일 토픽**이며, 테넌트/사용자별 구독 토픽이 없습니다. 모든 알림이 동일 토픽으로 브로드캐스트되고, **payload에 tenantId(및 선택적 userId)** 가 포함됩니다. FE에서 수신 후 **tenantId(및 필요 시 userId)** 로 필터링해야 합니다. |

---

## 8.4 REST API

| 확인 항목 | 백엔드 상태 | 비고 |
|-----------|-------------|------|
| **GET 목록** | **ApiResponse&lt;Page&lt;NotificationDto&gt;&gt;** | `GET /api/synapse/notifications` (Gateway 8080 기준 **GET /api/synapse/notifications**). **X-Tenant-ID** 필수(숫자). 응답: `{ "status": "SUCCESS", "message": "...", "data": { "content": [ NotificationDto 배열 ], "totalElements", "totalPages", "size", "number", "first", "last", ... } }`. DTO 필드는 WebSocket과 동일(camelCase, id/tenantId/userId는 number). |
| **PATCH 단건 읽음** | **바디 없음** | `PATCH /api/synapse/notifications/{id}`. **X-Tenant-ID** 필수. path variable **id**는 Long. 요청 바디 없음. 응답: `ApiResponse.success(null)` → `{ "status": "SUCCESS", "data": null, ... }`. |
| **PATCH 전체 읽음** | **쿼리 userId 선택** | `PATCH /api/synapse/notifications/read-all?userId={userId}`. **X-Tenant-ID** 필수. **userId** 쿼리 파라미터 선택(Long). 응답: `ApiResponse<NotificationReadAllResultDto>` → `data: { "markedCount": number }`. (FE에서 읽음 처리된 건수는 `data.markedCount` 로 사용 가능.) |

---

## 요약

- **WS**: SockJS + STOMP 로 연결 권장. Origin 기본 `*` 로 개발 환경 연결 가능.
- **페이로드**: camelCase, **link** 는 BE에서 채움. **type** 은 CASE_ACTION, ANALYSIS_STARTED, RAG_STATUS, AI_DETECT, GENERIC, UNKNOWN 등.
- **브로드캐스트**: 전역 `/topic/notifications` 이므로 FE에서 tenantId(·userId) 필터 필요.
- **REST**: GET은 Page 구조(content, totalElements, size, number 등), PATCH 읽음은 바디 없음, read-all은 `markedCount` 반환.
