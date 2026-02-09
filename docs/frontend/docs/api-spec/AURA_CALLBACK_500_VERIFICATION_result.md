# Aura 콜백 500 오류 — BE 확인 결과

> **대상**: Aura 팀  
> **작성일**: 2026-02-09  
> **참조**: aura.txt (Aura 확인 요청)

---

## 1. 조치 완료 사항

| 이슈 | 원인 | 조치 |
|------|------|------|
| 400 Bad Request (X-Tenant-ID) | Gateway가 콜백 경로에 X-Tenant-ID 필수 검증 | `/api/synapse/internal/` 제외 경로 추가 |
| 400 UnrecognizedPropertyException (caseId) | Aura가 caseId 전송, BE DTO에 없음 | `AuraCallbackPayload`에 `@JsonIgnoreProperties(ignoreUnknown = true)` 추가 |
| 500 ClassCastException (confidence) | `confidence` ObjectNode 강제 캐스팅 | `JsonNode` 그대로 사용하도록 변경 |
| createdAt 파싱 실패 | Python 마이크로초/타임존 없는 형식 | `LenientInstantDeserializer` 적용 |
| JSON parse → 500 | `HttpMessageNotReadableException` 미처리 | `GlobalExceptionHandler`에 400 핸들러 추가 |

---

## 2. AuraCallbackPayload 스키마 확정

| 필드 | 타입 | 필수 | 비고 |
|------|------|------|------|
| runId | UUID | O | |
| status | String | O | COMPLETED \| FAILED |
| auraTraceId | String | | |
| partialEvents | List\<Map\> | | |
| finalResult | Object | | status=COMPLETED 시 |
| **caseId** | (무시) | | Aura 전송 시 `@JsonIgnoreProperties`로 무시, BE는 runId로 조회 |

**finalResult.proposals[].createdAt**  
- ISO 8601 (타임존 있음/없음 모두 수용)  
- 예: `"2026-02-09T21:21:14.000Z"`, `"2026-02-09T12:25:01.328103"`

---

## 3. traceId 로그 검색

500 발생 시 `traceId`(예: `688ea51e-a530-4085-86d8-e20b93e3b3a2`)로 검색 시:

- `log.error("Unexpected error: ...", e)` 로 스택 트레이스 기록
- `HttpMessageNotReadableException` → 400 + E4002 (INVALID_FORMAT)로 응답

---

## 4. 최종 동작 확인

2026-02-09 21:25 이후 로그:

```
Aura callback: runId=1d002edd-98d2-460f-83a7-e2766ace414c status=COMPLETED
```

콜백 정상 수신·처리 확인했습니다.

---

## 5. Aura 측 추가 작업 불필요

- caseId: 전송해도 되고, BE에서 무시
- proposals[].createdAt: ISO 8601 (Z 유무 모두 수용)
- confidence: object/array 모두 수용

Aura 콜백 스펙 변경 없이 현재 payload 그대로 사용 가능합니다.

---

## 6. Stream `event:failed` 이슈 (202 응답)

**증상**: Aura가 202 Accepted 반환 시 BE가 run을 FAILED로 설정 → stream이 `event:failed` 발송

**원인**: Feign이 202 응답 시 예외를 던지고, BE가 이를 실패로 처리함

**조치**: 202 응답 시 run을 FAILED로 설정하지 않도록 수정 (202 = 수락, 콜백 대기)

- 수정 후: run은 STARTED 유지 → Aura 콜백 수신 시 COMPLETED → stream `event:completed` 발송
