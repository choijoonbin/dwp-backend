# 실시간 알림 스펙 요청 (FE → BE 확인용)

> 백엔드 담당자 확인 항목: WebSocket 서버·경로·메시지 형식·선택적 REST.

---

## 1. WebSocket — 실시간 알림 (우선 필요)

| 항목 | 요청 내용 |
|------|-----------|
| **엔드포인트** | `ws://localhost:8080/ws/notifications` (또는 운영 호스트/경로) |
| **경로** | `/ws/notifications` (합의 경로) |
| **메시지 형식 (JSON)** | FE가 파싱해 스토어·배지·목록·토스트에 사용 |

**필드 요청**: `id`, `type`(또는 category), `title`, `content`(또는 message/body), `link`, `occurredAt`/`createdAt` 등.

**type 예시**: `AI_DETECT`, `TRAINING_COMPLETE`, `APPROVAL_COMPLETE`, `CASE_ACTION`, `RAG_STATUS`, `GENERIC` (back.txt 4.3 등 기존 스펙과 맞출 것).

---

## 2. REST API — 선택 (초기 목록·읽음 처리)

| API | 용도 |
|-----|------|
| **GET** 알림 목록/개수 | 로그인 후 "기존 알림" 채울 때 |
| **PATCH** 알림 읽음 처리 / 전체 읽음 | "모두 읽음" 등 서버와 동기화 |

---

## 3. 백엔드 검증 결과

구현 확인 및 상세 스펙은 **NOTIFICATIONS_BACKEND_VERIFICATION.md** 참고.
