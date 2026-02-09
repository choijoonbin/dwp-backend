# 분석 스트림 종료 이벤트 계약

> **배경**: Aura 스트림이 `data: [DONE]`으로 종료되는데, FE는 `event: completed`를 기대하여 "스트림 오류" 처리함  
> **목적**: 종료 이벤트를 FE-BE-Aura 간 계약으로 명확히 함

---

## 1. 현재 상황

| 스트림 | 종료 방식 | FE 기대 |
|--------|-----------|---------|
| BE (`/api/synapse/.../stream`) | `event: completed` 또는 `event: failed` | ✅ 일치 |
| Aura (`/api/aura/.../stream`) | `data: [DONE]` | ❌ `event: completed` 기대 |

**FE 충돌**: `event: completed` 미수신 → "스트림 오류" + "분석이 완료되지 않았습니다"

---

## 2. 계약 (권장)

### 2.1 표준 종료 이벤트

스트림은 **반드시** 아래 중 하나로 종료되어야 함:

| 이벤트 | 의미 | data 예시 |
|--------|------|-----------|
| `event: completed` | 정상 완료 | `{"status":"completed","runId":"<uuid>"}` |
| `event: failed` | 실패 | `{"status":"failed","runId":"<uuid>","message":"<error>"}` |

### 2.2 Aura 권장 변경

`data: [DONE]` 직전에 `event: completed` 추가:

```
event: completed
data: {"status":"completed","runId":"b35f3df4-92e2-4543-bd07-5a39d5f7d28e","caseId":"85115"}

data: [DONE]
```

---

## 3. FE 임시 대응 (Aura 수정 전)

Aura 수정 전까지 FE에서 `data: [DONE]`을 **완료로 간주**:

```typescript
// SSE 파싱 시
if (event.type === 'message' && data === '[DONE]') {
  // event: completed와 동일하게 처리
  handleCompleted({ status: 'completed', runId });
}
```

또는 `event` 필드가 없는 `data:` 라인을 `[DONE]`이면 완료로 처리.

---

## 4. 정리

| 주체 | 조치 |
|------|------|
| **Aura** | `data: [DONE]` 직전에 `event: completed` 추가 (권장) |
| **FE** | Aura 수정 전: `data: [DONE]` 수신 시 완료로 처리 |
| **BE** | streamUrl은 Aura 전달, 계약 문서화 |

Aura가 `event: completed`를 추가하면 FE는 기존 로직 그대로 사용 가능합니다.
