# DWP Admin Monitoring API 명세서 (P1-2)

본 문서는 Admin Remote 통합 모니터링 대시보드를 위한 운영 로그 수집 및 조회 API 명세를 정의합니다.

**최종 업데이트**: 2026-01-19  
**버전**: P1-2 (Visitors/Events/Timeseries 고도화)

---

## 📋 사전 점검 결과

### 현재 스키마 상태
- `sys_page_view_events`: tenant_id BIGINT, page_key, session_id(visitorId), ip_address, user_agent, event_type/event_name/target_key/metadata_json 컬럼 존재
- `sys_page_view_daily_stats`: 일별 집계 테이블 존재 (tenant_id, stat_date, page_key 기준 UNIQUE)
- `sys_api_call_histories`: tenant_id BIGINT, Gateway에서 자동 수집 중
- `sys_event_logs`: 신규 테이블 추가 (P1-2)

### 인증 정책
- `/api/admin/**`: JWT 인증 필수 (JwtConfig에서 anyRequest().authenticated())
- `/api/monitoring/**`: 인증 제외 가능 (permitAll), 단 X-Tenant-ID 헤더 필수

### tenant_id 타입
- 모든 테이블: `BIGINT` (숫자)

### from/to 파라미터 포맷
- ISO-8601 형식 (예: `2026-01-01T00:00:00` 또는 `2026-01-01T00:00:00Z`)

---

## 1. 운영 로그 수집 API (Frontend -> Backend)

### 1.1 페이지뷰 수집
- **Endpoint**: `POST /api/monitoring/page-view`
- **Description**: 사용자의 페이지 방문 이력을 기록하고 일별 통계를 업데이트합니다.
- **인증**: 불필요 (permitAll)
- **Headers**:
  - `X-Tenant-ID`: 테넌트 식별자 (필수, 없으면 400)
  - `Authorization`: Bearer JWT (선택)
- **Request Body**:
```json
{
  "path": "/admin/monitoring",
  "menuKey": "menu.admin.monitoring",
  "title": "통합 모니터링 대시보드",
  "visitorId": "visitor_123",
  "device": "desktop",
  "referrer": "http://localhost:4200/dashboard",
  "userId": "optional",
  "metadata": {
    "browser": "Chrome",
    "os": "macOS"
  }
}
```
- **Validation**:
  - `path` 필수 (비어있으면 적재 금지)
  - 문자열 길이 제한: path(500), menuKey(255), visitorId(255), referrer(500)
- **Response**: 
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "accepted": true
  },
  "success": true
}
```
- **에러 처리**: 
  - X-Tenant-ID 없음 → 400 Bad Request
  - path 비어있음 → 400 Bad Request
  - 기타 오류 → Silent fail (로그만 기록, FE 영향 없음)

### 1.2 이벤트 수집
- **Endpoint**: `POST /api/monitoring/event`
- **Description**: 버튼 클릭 등 사용자 인터랙션 이력을 `sys_event_logs` 테이블에 기록합니다.
- **인증**: 불필요 (permitAll)
- **Headers**:
  - `X-Tenant-ID`: 테넌트 식별자 (필수, 없으면 400)
- **Request Body**:
```json
{
  "eventType": "view",
  "resourceKey": "menu.admin.users",
  "action": "view_users",
  "label": "Admin Users 조회",
  "visitorId": "visitor_123",
  "path": "/admin/users",
  "userId": "optional",
  "metadata": {
    "source": "frontend",
    "timestamp": "2026-01-19T16:00:00Z"
  }
}
```
- **Validation**:
  - `eventType`, `resourceKey`, `action` 필수
  - 문자열 길이 제한: eventType(50), resourceKey(255), action(100), label(200)
- **Response**: 
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "accepted": true
  },
  "success": true
}
```
- **저장 위치**: `sys_event_logs` 테이블
- **occurred_at**: 서버 현재 시간으로 저장 (metadata.timestamp는 참고용)

---

## 2. 모니터링 조회 API (Admin 전용)

**공통 사항**:
- 모든 조회 API는 JWT 인증 필수
- `X-Tenant-ID` 헤더 필수
- tenant_id 필터 무조건 적용
- 향후 ADMIN role 체크 확장 가능 (현재는 TODO)

### 2.1 대시보드 요약 정보 조회
- **Endpoint**: `GET /api/admin/monitoring/summary`
- **Query Parameters**:
  - `from`: 시작 일시 (ISO-8601, 필수)
  - `to`: 종료 일시 (ISO-8601, 필수)
- **Response**:
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "pv": 149,
    "uv": 4,
    "events": 114,
    "apiErrorRate": 0.5,
    "pvDeltaPercent": 11.8,
    "uvDeltaPercent": 33.3,
    "eventDeltaPercent": 5.2,
    "apiErrorDeltaPercent": -2.1
  },
  "success": true
}
```

### 2.2 페이지뷰 목록 조회 (페이징)
- **Endpoint**: `GET /api/admin/monitoring/page-views`
- **Query Parameters**:
  - `page`: 페이지 번호 (default 0, 0-base)
  - `size`: 페이지 크기 (default 20)
- **Response**: `ApiResponse<Page<PageViewEvent>>`

### 2.3 API 호출 이력 조회 (페이징)
- **Endpoint**: `GET /api/admin/monitoring/api-histories`
- **Query Parameters**:
  - `page`: 페이지 번호 (default 0, 0-base)
  - `size`: 페이지 크기 (default 20)
- **Response**: `ApiResponse<Page<ApiCallHistory>>`

### 2.4 방문자 목록 조회 (신규, P1-2)
- **Endpoint**: `GET /api/admin/monitoring/visitors`
- **Query Parameters**:
  - `page`: 페이지 번호 (default 1, 1-base)
  - `size`: 페이지 크기 (default 10)
  - `from`: 시작 일시 (ISO-8601, 필수)
  - `to`: 종료 일시 (ISO-8601, 필수)
  - `keyword`: 검색 키워드 (visitorId 또는 path, 선택)
- **Response**:
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "content": [
      {
        "visitorId": "visitor_123",
        "firstSeenAt": "2026-01-19T10:00:00",
        "lastSeenAt": "2026-01-19T16:00:00",
        "pageViewCount": 15,
        "eventCount": 8,
        "lastPath": "/admin/monitoring",
        "lastDevice": null,
        "lastUserId": 1
      }
    ],
    "totalElements": 10,
    "totalPages": 1,
    "size": 10,
    "number": 0
  },
  "success": true
}
```
- **집계 기준**:
  - `sys_page_view_events` 기준으로 first/last/pageViewCount 계산
  - `sys_event_logs` 기준으로 eventCount 계산 (현재는 전체, 향후 visitorId별 개선)
  - visitorId가 null/empty인 데이터는 `visitorId="anonymous"`로 매핑

### 2.5 이벤트 로그 목록 조회 (신규, P1-2)
- **Endpoint**: `GET /api/admin/monitoring/events`
- **Query Parameters**:
  - `page`: 페이지 번호 (default 1, 1-base)
  - `size`: 페이지 크기 (default 10)
  - `from`: 시작 일시 (ISO-8601, 필수)
  - `to`: 종료 일시 (ISO-8601, 필수)
  - `eventType`: 이벤트 타입 필터 (선택)
  - `resourceKey`: 리소스 키 필터 (선택)
  - `keyword`: 검색 키워드 (action/label/path, 선택)
- **Response**:
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "content": [
      {
        "sysEventLogId": 1,
        "occurredAt": "2026-01-19T16:00:00",
        "eventType": "view",
        "resourceKey": "menu.admin.users",
        "action": "view_users",
        "label": "Admin Users 조회",
        "visitorId": "visitor_123",
        "userId": 1,
        "path": "/admin/users",
        "metadata": {
          "source": "frontend"
        }
      }
    ],
    "totalElements": 50,
    "totalPages": 5,
    "size": 10,
    "number": 0
  },
  "success": true
}
```
- **정렬**: `occurred_at DESC` (최신순)

### 2.6 시계열 데이터 조회 (신규, P1-2)
- **Endpoint**: `GET /api/admin/monitoring/timeseries`
- **Query Parameters**:
  - `from`: 시작 일시 (ISO-8601, 필수)
  - `to`: 종료 일시 (ISO-8601, 필수)
  - `interval`: 집계 간격 (`HOUR` | `DAY`, default `DAY`)
  - `metric`: 메트릭 타입 (`PV` | `UV` | `EVENT` | `API_TOTAL` | `API_ERROR`, default `PV`)
- **Response**:
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "interval": "DAY",
    "metric": "PV",
    "labels": [
      "2026-01-01",
      "2026-01-02",
      "2026-01-03"
    ],
    "values": [
      100,
      150,
      120
    ]
  },
  "success": true
}
```
- **집계 기준**:
  - `PV`: sys_page_view_events count (interval=DAY는 sys_page_view_daily_stats 우선 사용)
  - `UV`: distinct(visitor_id) count (visitor_id null이면 제외)
  - `EVENT`: sys_event_logs count
  - `API_TOTAL`: sys_api_call_histories count
  - `API_ERROR`: sys_api_call_histories where status_code >= 400 count
- **성능 최적화**:
  - `interval=DAY`: sys_page_view_daily_stats 우선 사용
  - `interval=HOUR`: raw 테이블 group by date_trunc('hour', occurred_at) 사용

---

## 3. 테스트 방법 (curl)

### 3.1 페이지뷰 수집
```bash
curl -X POST "http://localhost:8080/api/monitoring/page-view" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: 1" \
  -d '{
    "path": "/admin/monitoring",
    "menuKey": "menu.admin.monitoring",
    "title": "통합 모니터링 대시보드",
    "visitorId": "visitor_123",
    "device": "desktop",
    "referrer": "http://localhost:4200/dashboard"
  }'
```

### 3.2 이벤트 수집
```bash
curl -X POST "http://localhost:8080/api/monitoring/event" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: 1" \
  -d '{
    "eventType": "view",
    "resourceKey": "menu.admin.users",
    "action": "view_users",
    "label": "Admin Users 조회",
    "visitorId": "visitor_123",
    "path": "/admin/users",
    "metadata": {"source":"frontend"}
  }'
```

### 3.3 방문자 목록 조회
```bash
curl -X GET "http://localhost:8080/api/admin/monitoring/visitors?page=1&size=10&from=2026-01-01T00:00:00&to=2026-01-31T23:59:59" \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1"
```

### 3.4 이벤트 로그 목록 조회
```bash
curl -X GET "http://localhost:8080/api/admin/monitoring/events?page=1&size=10&from=2026-01-01T00:00:00&to=2026-01-31T23:59:59" \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1"
```

### 3.5 시계열 데이터 조회
```bash
curl -X GET "http://localhost:8080/api/admin/monitoring/timeseries?from=2026-01-01T00:00:00&to=2026-01-31T23:59:59&interval=DAY&metric=PV" \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1"
```

---

## 4. 보안 정책

### 4.1 수집 API (`/api/monitoring/**`)
- **인증**: 불필요 (permitAll)
- **X-Tenant-ID**: 필수 (없으면 400 또는 204)
- **Abuse 방지**:
  - page-view: (tenant_id, visitor_id, path) 기준 1초 이내 중복 체크 (TODO: Redis 기반 분산 락으로 개선 가능)
  - event: eventType/action 길이 제한

### 4.2 조회 API (`/api/admin/**`)
- **인증**: JWT 필수
- **권한 체크**: 향후 ADMIN role 체크 확장 가능 (현재는 TODO)
- **X-Tenant-ID**: 필수, tenant_id 필터 무조건 적용

### 4.3 CORS
- 기존 설정 유지
- X-Tenant-ID, X-User-ID, Authorization 헤더 허용

---

## 5. 데이터 정책

### 5.1 tenant_id 분리
- 모든 모니터링 데이터는 tenant_id로 분리 저장/조회
- 조회 API에서 tenant_id 필터 무조건 적용

### 5.2 UV 정책
- visitor_id가 null/empty인 데이터는 UV 집계에서 제외 (또는 anonymous=1 처리)
- 현재 정책: visitor_id null이면 제외

### 5.3 Anonymous 방문자 정책
- visitorId가 null/empty인 데이터는 `visitorId="anonymous"`로 매핑하여 응답
- Visitors 조회 API에서만 적용

---

## 6. 테이블 구조

### sys_event_logs (신규, P1-2)
- `sys_event_log_id`: PK
- `tenant_id`: 테넌트 식별자 (BIGINT)
- `occurred_at`: 이벤트 발생 시간 (TIMESTAMP)
- `event_type`: 이벤트 타입 (VARCHAR(50))
- `resource_key`: 리소스 키 (VARCHAR(255))
- `action`: 액션 (VARCHAR(100))
- `label`: UI 표시용 라벨 (VARCHAR(200))
- `visitor_id`: 방문자 식별자 (VARCHAR(255))
- `user_id`: 사용자 ID (BIGINT)
- `path`: 경로 (VARCHAR(500))
- `metadata`: 추가 데이터 (JSONB)
- `ip_address`: IP 주소 (VARCHAR(50))
- `user_agent`: User-Agent (TEXT)
- `created_at/by, updated_at/by`: 감사 필드

**인덱스**:
- `(tenant_id, occurred_at DESC)`
- `(tenant_id, visitor_id)`
- `(tenant_id, resource_key)`

---

## 7. 프론트엔드 통합 가이드

### 7.1 수집 API 호출 예시
```typescript
// 페이지뷰 수집
await fetch('/api/monitoring/page-view', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Tenant-ID': tenantId
  },
  body: JSON.stringify({
    path: window.location.pathname,
    menuKey: currentMenuKey,
    title: document.title,
    visitorId: getVisitorId(),
    device: getDeviceType(),
    referrer: document.referrer
  })
});

// 이벤트 수집
await fetch('/api/monitoring/event', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Tenant-ID': tenantId
  },
  body: JSON.stringify({
    eventType: 'click',
    resourceKey: 'btn.search',
    action: 'click_search',
    label: '검색 버튼 클릭',
    visitorId: getVisitorId(),
    path: window.location.pathname
  })
});
```

### 7.2 조회 API 호출 예시
```typescript
// 방문자 목록 조회
const visitors = await fetch(
  `/api/admin/monitoring/visitors?page=1&size=10&from=${from}&to=${to}`,
  {
    headers: {
      'Authorization': `Bearer ${token}`,
      'X-Tenant-ID': tenantId
    }
  }
).then(res => res.json());

// 이벤트 로그 조회
const events = await fetch(
  `/api/admin/monitoring/events?page=1&size=10&from=${from}&to=${to}&eventType=click`,
  {
    headers: {
      'Authorization': `Bearer ${token}`,
      'X-Tenant-ID': tenantId
    }
  }
).then(res => res.json());

// 시계열 데이터 조회
const timeseries = await fetch(
  `/api/admin/monitoring/timeseries?from=${from}&to=${to}&interval=DAY&metric=PV`,
  {
    headers: {
      'Authorization': `Bearer ${token}`,
      'X-Tenant-ID': tenantId
    }
  }
).then(res => res.json());
```

---

## 8. 완료 체크리스트 (P1-2)

- [x] sys_event_logs 테이블 생성 (V11)
- [x] EventLog 엔티티 및 Repository 생성
- [x] 수집 API 정리 (page-view, event)
- [x] Visitors 조회 API 추가
- [x] Events 조회 API 추가
- [x] Timeseries API 추가
- [x] 보안 정책 정리 및 적용
- [x] 컨트롤러 분리 (수집/조회)
- [ ] 테스트 작성 (JUnit5)
- [x] 문서 업데이트

---

## 9. 향후 개선 사항

### 9.1 Abuse 방지 강화
- Redis 기반 분산 락으로 중복 방지 개선
- Rate limiting 적용

### 9.2 성능 최적화
- Timeseries 쿼리 최적화 (인덱스 튜닝)
- 방문자별 eventCount 집계 개선

### 9.3 권한 관리
- ADMIN role 체크 구현
- 테넌트별 접근 제어 강화

