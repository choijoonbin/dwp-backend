# WebSocket 알림 파이프라인 정합성 정리

> 이 문서는 웹소켓 알림 Lifecycle과 시스템별 역할을 정리하고, 현재 Backend 구현과의 정합성을 명시합니다.

## 1. 현재 설계된 Lifecycle (문서 기준)

| 단계 | 설명 | 비고 |
|------|------|------|
| **연결** | FE layout 마운트 시 연결 | Gateway 경유 → Backend |
| **통로** | `/api/synapse/ws-notifications` 또는 `/ws/notifications` | Gateway: `/ws/**` 및 `/api/synapse/ws-notifications/**` → Backend `/ws/notifications` |
| **인증** | STOMP connectHeaders에 Bearer Token | ChannelInterceptor에서 로깅 가능 |
| **트리거** | Aura가 Redis `workbench:case:action` 등에 이벤트 발행 | ANALYSIS_STARTED 등 |
| **수신** | Backend가 Redis 메시지 수신 후 WebSocket 브로드캐스트 | |
| **브로드캐스트** | `/topic/notifications` 또는 `/topic/notifications/{tenantId}` | ⬇️ 현재 BE는 단일 토픽 |
| **구독** | FE가 동일 경로 구독 후 onReceive → 스토어 갱신 | |
| **종료** | 로그아웃/탭 종료 시 연결 해제 | |

## 2. Backend 실제 구현과 문서 정합성

### 2.1 Redis 구독 주체

- **문서 표현**: "AuditEventRedisSubscriber가 Redis 메시지를 수신하여 NotificationBroadcastService 호출"
- **실제 구현**:
  - **알림용 Redis 수신**: **NotificationRedisSubscriber** 가 패턴 **workbench:*** (PSUBSCRIBE)로 구독 → `workbench:case:action`, `workbench:rag:status` 등 수신 → **NotificationBroadcastService.saveAndBroadcast()** 호출
  - **AuditEventRedisSubscriber**: 채널 **audit:events:ingest** 전용 구독 → **AuditEventIngestService** 로 감사 로그 저장 (알림 브로드캐스트와 무관)

정리: **알림 파이프라인**은 **NotificationRedisSubscriber** 가 담당하며, AuditEventRedisSubscriber는 감사 로그 수집 전용입니다.

### 2.2 브로드캐스트 경로

- **문서 일부**: "SimpMessagingTemplate으로 `/topic/notifications/{tenantId}` 로 전송"
- **현재 BE**: **`/topic/notifications`** 단일 토픽 사용. 테넌트 격리 경로 미사용.
  - **NotificationBroadcastService**: `convertAndSend("/topic/notifications", dto)`
  - DTO에 **tenantId** 포함 → FE에서 `payload.tenantId`로 필터링

FE와의 규격 일치를 위해 **FE는 `/topic/notifications` 구독**이면 됩니다.  
향후 `/topic/notifications/{tenantId}` 로 전환할 경우, BE 전송 경로와 FE 구독 경로를 동시에 변경해야 합니다.

### 2.3 연결 엔드포인트

- **문서**: "통로 `/api/synapse/ws-notifications` (Gateway 경유)"
- **실제 Gateway**:
  - **Path**: `/ws/**` → SynapseX (`synapsex-ws-notifications`)
  - **Path 별칭**: `/api/synapse/ws-notifications/**` → RewritePath로 `/ws/notifications**` → SynapseX (`synapsex-ws-notifications-alias`)
  - **URI 프로토콜**: **ws://** (또는 `SERVICE_SYNAPSEX_WS_URL`) 사용 → **WebsocketRoutingFilter**가 101 Switching Protocols 처리.
  - **경로 보존**: RewritePath `(?<segment>.*)` 로 SockJS 동적 경로(예: `/027/jmkdjmow/websocket`)가 잘리지 않고 그대로 백엔드로 전달됨.
  - **Upgrade: websocket**: Spring Cloud Gateway는 프록시 시 해당 헤더를 다운스트림으로 자동 전달. **RequestBodyLoggingFilter**, **SseResponseHeaderFilter**, **SseReconnectionFilter**, **RequiredHeaderFilter**에서 웹소켓 경로(`/ws/`, `/api/synapse/ws-notifications`) 제외하여 101 간섭 방지.
  - **Backend 엔드포인트**: `/ws/notifications` (SockJS + STOMP)

FE 연결 URL 예: `http://localhost:8080/ws/notifications` 또는 `http://localhost:8080/api/synapse/ws-notifications` (동일 동작).

## 3. 시스템별 보완 사항 반영 현황

### [Backend] 메시지 브로커 및 Redis 연동

| 보완 항목 | 반영 내용 |
|-----------|-----------|
| Redis 구독 확인 | NotificationRedisConfig 기동 시 `"Notification Redis listener subscribed to pattern: workbench:* (includes workbench:case:action)"` 로그 |
| Redis 수신 로깅 | NotificationRedisSubscriber.onMessage 에서 **INFO**: `"Redis notification received channel={} pattern={}"` (실시간 모니터링용). **DEBUG**: channel, pattern, 페이로드 전체 |
| 브로드캐스트 전 로깅 | NotificationBroadcastService 에서 destination, type, tenantId, id 로그 (실패 시 destination 포함 WARN) |

### [Backend] STOMP 인증/핸드셰이크 디버깅

| 보완 항목 | 반영 내용 |
|-----------|-----------|
| CONNECT 로깅 | ChannelInterceptor 에서 CONNECT 시 sessionId, principal, Authorization 헤더 존재 여부 **log.info** |
| SUBSCRIBE/DISCONNECT | sessionId, destination **log.debug** |
| 권한 부족/거절 | afterSendCompletion 에서 예외 시 command, sessionId, 메시지 **log.warn** ("권한 부족 또는 메시지 거절 가능성") |

### [Frontend] 구독 경로 및 인증

- **현재 BE**: `/topic/notifications` 단일 토픽 → **FE 구독 경로 `/topic/notifications`** 와 일치.
- 테넌트는 **payload.tenantId** 로 필터링.
- Gateway에서 WebSocket `/ws/**` 는 CORS/RequiredHeader 제외·SSE 필터 제외 등 적용 완료.

### [Aura] Redis 채널 및 페이로드

- BE는 **workbench:*** 패턴 구독으로 type/category 가 Enum과 다르더라도 **문자열로 수신** 후 NotificationDto.type 에 그대로 매핑.
- 역직렬화 실패 시 NotificationRedisSubscriber 에서 `log.warn("Notification Redis parse/broadcast failed ...")` 로 남김.  
Aura 측 Redis 발행 직전 페이로드 로깅은 Aura 코드에서 보완 필요.

## 4. 요약

- **브로드캐스트 경로 확정**: **NotificationBroadcastService.WS_TOPIC = "/topic/notifications"** (코드 상수).
- **Redis → 알림**: **NotificationRedisSubscriber** (workbench:*) → **NotificationBroadcastService** → **/topic/notifications**.
- **경로**: BE 전송 = FE 구독 = **/topic/notifications** (테넌트는 payload 기준).
- **연결**: Gateway **/ws/** 또는 **/api/synapse/ws-notifications/** → Backend **/ws/notifications**. Upgrade: websocket 헤더 통과.
- **로깅**: Redis 수신 시 INFO `"Redis notification received channel=... pattern=..."`, DEBUG에 페이로드. 브로드캐스트 destination/타입, STOMP CONNECT/SUBSCRIBE/DISCONNECT 및 전송 예외 반영됨.

---

## 5. [BE] 웹소켓 안정성·인프라 점검 체크리스트

| 항목 | 확인 내용 | 위치/방법 |
|------|-----------|-----------|
| **브로드캐스트 경로** | `WS_TOPIC` 이 **`/topic/notifications`** 인지 | `NotificationBroadcastService.java` 상수 `WS_TOPIC` |
| **Gateway 소켓 업그레이드** | FE 404/연결 실패 시: `/ws/**` 및 `/api/synapse/ws-notifications/**` 에 대해 **Upgrade: websocket** 이 차단되지 않는지 | Gateway `application*.yml` 라우트(**uri: ws://** 사용), Nginx 등 프록시에서 해당 경로 `Upgrade`/`Connection` 제거 여부 점검. 500 발생 시 `logging.level.org.springframework.cloud.gateway: DEBUG`, `reactor.netty.http.server: DEBUG` 로 Stacktrace 확보 |
| **Redis 패턴 수신** | **workbench:*** 구독으로 **workbench:case:action** 수신 시 **"Redis notification received channel=... pattern=..."** 로그 출력 | SynapseX 로그 레벨 INFO 이상에서 `NotificationRedisSubscriber` 로그 확인; 실시간 모니터링: `tail -f` 또는 로그 수집기에서 위 문자열 검색 |

---

## 6. [BE] Gateway 라우팅 및 Redis-WS 브릿지 최종 확인 (로그 기준)

| 확인 항목 | 성공 시 로그/확인 방법 |
|-----------|------------------------|
| **Gateway 101 핸드셰이크** | `/api/synapse/ws-notifications` 요청 시 **Upgrade: websocket** 이 필터에서 변형 없이 다운스트림 전달. **Gateway 로그**: `WebSocket upgrade request forwarded path=... (Upgrade header passed to downstream, WebsocketRoutingFilter will handle 101)` 출력 시 정상. `uri: ws://` 로 WebsocketRoutingFilter 101 처리. |
| **Redis 메시지 중계** | Aura `workbench:case:action` 수신 후 **NotificationBroadcastService**에서 `/topic/notifications` 브로드캐스트 성공 시 **SynapseX 로그**: `Notification broadcast succeeded destination=/topic/notifications type=... tenantId=... id=... (Redis→WS bridge, e.g. workbench:case:action)` 출력. |
| **인증 인터셉터 예외** | **ChannelInterceptor**는 CONNECT 시 Token 검증 실패로 거절하지 않음. **SynapseX 로그**: CONNECT 시 `STOMP CONNECT allowed sessionId=... hasAuthorization=...` 출력. 즉시 DISCONNECT 시 `STOMP afterSendCompletion error ... (Token 검증 실패로 DISCONNECT 가능성)` 선행 여부로 원인 구분. |

