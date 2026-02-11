# WebSocket 알림 엔드포인트·메시지 형식 확인 회신 (FE 확인 요청 #4)

> **요청**: WebSocket 엔드포인트, URL 오버라이드, 메시지 형식 및 category 규약  
> **회신 일자**: 2026-02-11

---

## 1. WebSocket 엔드포인트

| 항목 | 내용 |
|------|------|
| **경로** | `/ws/notifications` (프로젝트 규약) |
| **프로토콜** | STOMP over SockJS over WebSocket |
| **Gateway 경유** | `GET` 업그레이드: `http://{gateway}:8080/ws/notifications` → SynapseX로 프록시 |
| **실제 연결 URL 예** | `http://localhost:8080/ws/notifications` (Gateway 기준, SockJS가 WebSocket 경로 자동 협상) |

- **엔드포인트 고정**: 백엔드는 **`/ws/notifications`** 만 사용합니다. (예: `/custom/notifications` 없음.)
- **구독 토픽**: 연결 후 STOMP 구독 경로 **`/topic/notifications`** 로 실시간 알림 수신.

---

## 2. URL 오버라이드 (NX_WS_URL)

- **권장 환경 변수**: `NX_WS_URL` (또는 FE 규약에 맞는 이름)
- **의미**: WebSocket(SockJS) **전체 베이스 URL**. 미설정 시 `NX_API_URL`(또는 Gateway origin) + `/ws/notifications` 사용 권장.
- **예시**  
  - 개발: `NX_WS_URL=http://localhost:8080` → 연결 경로: `http://localhost:8080/ws/notifications`  
  - 또는 전체 지정: `NX_WS_URL=http://localhost:8080/ws/notifications` (FE에서 경로 포함해 쓰는 경우)
- **프로덕션**: `wss://{gateway-host}/ws/notifications` 등으로 동일 규약 적용.

---

## 3. 메시지 형식 (JSON) 및 category 규약

**수신 payload**: 알림 한 건당 아래 JSON (백엔드 `NotificationDto` 직렬화).

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | number | sys_notifications PK (저장 이력 조회 시 동일) |
| `tenantId` | number | 테넌트 ID (알림 센터에서 테넌트 필터용) |
| `userId` | number \| null | null = 전체 브로드캐스트 |
| `title` | string | 알림 제목 (상단 알림 바·드롭다운 표시용) |
| `content` | string | 본문 요약 |
| **`type`** | string | **알림 유형 = category 규약 값** (아래 표) |
| `channel` | string | 발신 Redis 채널 (예: workbench:case:action) |
| `occurredAt` | string | ISO-8601 시각 (이벤트 발생 시각) |
| `createdAt` | string | ISO-8601 (DB 저장 시각) |
| `readAt` | string \| null | 읽음 시각 (미구현 시 null) |
| `payload` | object \| null | 원본 Redis payload (case_id, action_type 등) |

**category 규약 (type 값)**:

| type (category) | 설명 |
|------------------|------|
| `CASE_ACTION` | 조치 완료 (승인/거절) — 제목 예: "조치 완료" |
| `RAG_STATUS` | RAG 문서 상태 변경 — 제목 예: "RAG 문서 상태" |
| `GENERIC` | 기타 workbench:* 이벤트 |

- FE에서는 **`type`** 필드를 **category** 로 해석해 알림 센터 드롭다운·필터/배지에 사용하면 됩니다.

**수신 예시**:
```json
{
  "id": 1,
  "tenantId": 1,
  "userId": null,
  "title": "조치 완료",
  "content": "케이스 DEMO00001 승인",
  "type": "CASE_ACTION",
  "channel": "workbench:case:action",
  "occurredAt": "2026-02-11T12:00:00.000Z",
  "createdAt": "2026-02-11T12:00:00.100Z",
  "readAt": null,
  "payload": {
    "type": "case_action_completed",
    "case_id": "DEMO00001",
    "approved": true,
    "action_type": "APPROVE"
  }
}
```

---

## 4. 정리

- **엔드포인트**: `GET` 업그레이드 기준 **`/ws/notifications`** (고정).
- **URL 오버라이드**: `NX_WS_URL` 로 베이스(또는 전체 URL) 지정 가능.
- **메시지 형식**: 위 JSON; **category = `type`** 값 **CASE_ACTION | RAG_STATUS | GENERIC** 규약.
- 이 구성을 기준으로 **웹소켓 수신부**와 **알림 센터 드롭다운 UI** 연동하면 됩니다.
