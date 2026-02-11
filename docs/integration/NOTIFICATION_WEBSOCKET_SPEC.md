# Real-time Notification Bridge (WebSocket)

## 개요

- Redis **패턴 구독(PSUBSCRIBE)** `workbench:*` 로 Aura가 발행하는 모든 workbench 채널을 상시 수신하고, 수신 이벤트를 **NotificationDto**로 변환 후:
  1. **WebSocket** `/topic/notifications` 로 브로드캐스트 (실시간 알림)
  2. **DB** `dwp_aura.sys_notifications` 에 저장 (나중에 다시 보기)

## WebSocket

| 항목 | 내용 |
|------|------|
| **엔드포인트** | `/ws/notifications` (SockJS fallback 지원) |
| **브로커 토픽** | `/topic/notifications` — 구독 시 실시간 알림 수신 |
| **Gateway** | `/ws/**` → SynapseX (8085) |

**연결 예 (FE)**  
- STOMP over SockJS: `ws://{gateway}/ws/notifications` (또는 `wss://`)  
- 구독: `client.subscribe('/topic/notifications', callback)`  
- 수신 payload: **NotificationDto** (title, content, type, occurredAt, tenantId, payload 등)

## Event 매핑

| Redis 채널 (실제 수신 채널명) | type | 제목 예시 |
|------------------------------|------|-----------|
| workbench:case:action | CASE_ACTION | 조치 완료 |
| workbench:rag:status | RAG_STATUS | RAG 문서 상태 |
| workbench:* 기타 | GENERIC | 알림 |

- **occurredAt**: Redis payload `at` (ISO-8601) 또는 수신 시각  
- **tenantId**: payload `tenant_id` (FE에서 테넌트별 필터 가능)

## Audit (DB 저장)

- 테이블: **dwp_aura.sys_notifications**
- 컬럼: id, tenant_id, user_id, title, content, type, channel, occurred_at, created_at, read_at, payload_json
- 발송된 모든 알림이 저장되며, **GET /api/synapse/notifications** 로 페이징 조회 가능

## 설정

| 키 | 기본값 | 설명 |
|------|--------|------|
| notification.redis.enabled | true | Redis 패턴 구독 활성화 |
| notification.redis.workbench-pattern | workbench:* | PSUBSCRIBE 패턴 (Aura workbench 채널 일괄 수신) |
| notification.websocket.allowed-origins | * | WebSocket CORS 허용 오리진 |
| workbench.redis.action-channel | workbench:case:action | 조치 완료 발행 채널 (기존, 패턴에 포함됨) |

## API

- **GET /api/synapse/notifications** — 알림 목록 (X-Tenant-ID 필수, page/size/sort)
- **WebSocket** — 실시간 수신만 (발송 이력은 위 GET으로 조회)
