# Case Detail 탭 API — 스모크 테스트 & 계약 정합

> DoD: 4개 엔드포인트 200 응답, curl 결과/응답샘플/필드정의/에러정책 문서화

---

## 1) 스모크 테스트 (curl)

### 공통 헤더

```
X-Tenant-ID: 1
Authorization: Bearer <TOKEN>
X-User-ID: 1 (선택)
```

### caseId

- seed 데이터 기준: `agent_case.case_id` (Long, 예: 1)
- tenant_id=1인 케이스 1건 존재 시 사용

### curl 명령 (Gateway 8080 경유)

```bash
# 공통 변수
BASE="http://localhost:8080/api/synapse/cases/1"
H="-H 'X-Tenant-ID: 1' -H 'Authorization: Bearer <TOKEN>'"

# 4개 엔드포인트 호출 (응답시간 포함)
curl -s -w "\nHTTP_CODE:%{http_code} TIME_MS:%{time_total}\n" $H "$BASE/analysis"
curl -s -w "\nHTTP_CODE:%{http_code} TIME_MS:%{time_total}\n" $H "$BASE/confidence"
curl -s -w "\nHTTP_CODE:%{http_code} TIME_MS:%{time_total}\n" $H "$BASE/similar"
curl -s -w "\nHTTP_CODE:%{http_code} TIME_MS:%{time_total}\n" $H "$BASE/rag/evidence"
```

### 직접 호출 (synapsex 8085, Gateway 없이)

```bash
curl -s -w "\nHTTP_CODE:%{http_code} TIME_MS:%{time_total}\n" \
  -H "X-Tenant-ID: 1" -H "Authorization: Bearer stub" \
  "http://localhost:8085/synapse/cases/1/analysis"
```

### 예상 결과 (Aura 미연동 또는 Empty Fallback 시)

| Endpoint | Status | Response Body (data) | 응답시간 |
|----------|--------|----------------------|----------|
| /analysis | 200 | `{"summary":null,"sections":[]}` | ~50–200ms |
| /confidence | 200 | `{"score":null,"factors":[]}` | ~50–200ms |
| /similar | 200 | `{"items":[]}` | ~50–200ms |
| /rag/evidence | 200 | `{"items":[]}` | ~50–200ms |

### 전체 응답 래퍼 (ApiResponse)

```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { ... },
  "success": true,
  "timestamp": "2026-02-06T18:35:00"
}
```

### 에러 시 (caseId 미존재)

- **404**: `{"status":"ERROR","message":"케이스를 찾을 수 없습니다.","errorCode":"E3000","success":false,...}`

---

## 2) 계약(스키마) 고정

### 2.1 최상위 필드 (ApiResponse 래퍼)

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| status | string | ✅ | "SUCCESS" \| "ERROR" |
| message | string | ✅ | 요청 처리 메시지 |
| data | object | ✅ | 탭별 payload (성공 시) |
| success | boolean | ✅ | status=="SUCCESS" 시 true |
| timestamp | string | ✅ | ISO 8601 |
| errorCode | string | - | 에러 시 (E3000 등) |
| traceId | string | - | 에러 시 추적 ID |
| gatewayRequestId | string | - | 에러 시 요청 ID |

### 2.2 data — /analysis

| 필드 | 타입 | 필수 | Empty 정책 |
|------|------|------|------------|
| summary | string \| null | - | null → hide |
| sections | array | ✅ | [] → 빈 배열 표시 |

### 2.3 data — /confidence

| 필드 | 타입 | 필수 | Empty 정책 |
|------|------|------|------------|
| score | number \| null | - | null → hide |
| factors | array | ✅ | [] → "항목 없음" 또는 hide |

**FE confidence 탭**: `factors` 렌더링. `factors`가 null/빈 배열이면 **hide** 또는 "신뢰도 데이터 없음" 표시.

### 2.4 data — /similar

| 필드 | 타입 | 필수 | Empty 정책 |
|------|------|------|------------|
| items | array | ✅ | [] → "유사 케이스 없음" 표시 |

### 2.5 data — /rag/evidence

| 필드 | 타입 | 필수 | Empty 정책 |
|------|------|------|------------|
| items | array | ✅ | [] → "증거 없음" 표시 |

### 2.6 Empty 정책 요약

| 상황 | 정책 |
|------|------|
| null | hide (UI에서 해당 영역 숨김) |
| 빈 배열 [] | "항목 없음" / "데이터 없음" 메시지 표시 |
| score 0 | 0으로 표시 (숫자 필드) |

---

## 3) 에러 계약

### 표준 에러 응답 (ApiResponse)

```json
{
  "status": "ERROR",
  "message": "<에러 메시지>",
  "errorCode": "<E코드>",
  "success": false,
  "timestamp": "2026-02-06T18:35:00",
  "traceId": "<optional>",
  "gatewayRequestId": "<optional>"
}
```

### HTTP Status ↔ errorCode 매핑

| HTTP | errorCode | message 예시 |
|------|-----------|--------------|
| 400 | E1001 / E4000 | 필수 헤더 'X-Tenant-ID'가 누락되었습니다. |
| 401 | E2000 | 인증이 필요합니다. |
| 403 | E2001 | 권한이 없습니다. |
| 404 | E3000 | 케이스를 찾을 수 없습니다. |
| 500 | E1000 | 내부 서버 오류가 발생했습니다. |
| 502 | E5000 | 외부 서비스 오류가 발생했습니다. |
| 504 | E5001 | 외부 서비스 응답 시간이 초과되었습니다. |

### 발생 조건

| 조건 | HTTP | errorCode |
|------|------|-----------|
| X-Tenant-ID 누락 | 400 | E1001 |
| JWT 만료/무효 | 401 | E2000/E2002/E2003 |
| 권한 없음 | 403 | E2001 |
| caseId 미존재(tenant 불일치) | 404 | E3000 |
| BE 내부 예외 | 500 | E1000 |
| Aura 5xx/연결실패 | 502 | E5000 (fallback 시 200+empty) |
| Aura timeout | 504 | E5001 (fallback 시 200+empty) |

**참고**: Aura 실패 시 현재 구현은 **200 + empty state** fallback을 반환하여 DoD를 충족함.

---

## 4) 검증 체크리스트

- [ ] 4개 엔드포인트 모두 200 응답 (최소 빈 구조)
- [ ] X-Tenant-ID 없으면 400
- [ ] caseId 미존재 시 404
- [ ] data 내 factors/items 등 null·빈배열 시 FE 정책 적용 가능
