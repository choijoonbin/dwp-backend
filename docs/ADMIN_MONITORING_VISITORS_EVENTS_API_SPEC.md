# Admin 모니터링 API 규격서 (Visitors & Events)

## 개요
Admin 모니터링 대시보드에서 사용하는 방문자(Visitors) 및 이벤트 로그(Events) 조회 API입니다.

---

## 1. 방문자 목록 조회 API

### 엔드포인트
```
GET /api/admin/monitoring/visitors
```

### 인증
- **필수**: JWT 토큰 (`Authorization: Bearer {JWT}`)
- **필수**: `X-Tenant-ID` 헤더

### 요청 파라미터

| 파라미터 | 타입 | 필수 | 기본값 | 설명 | 예시 |
|---------|------|------|--------|------|------|
| `page` | integer | 아니오 | `1` | 페이지 번호 (1부터 시작) | `1` |
| `size` | integer | 아니오 | `10` | 페이지당 항목 수 | `10` |
| `from` | string (ISO 8601) | 아니오 | 현재 시간 - 30일 | 시작 일시 | `2026-01-01T00:00:00` |
| `to` | string (ISO 8601) | 아니오 | 현재 시간 | 종료 일시 | `2026-01-31T23:59:59` |
| `keyword` | string | 아니오 | - | 검색 키워드 (visitorId 또는 path) | `visitor_123` |

### 요청 예시

#### 기본 요청 (최근 30일)
```bash
GET /api/admin/monitoring/visitors?page=1&size=10
Headers:
  Authorization: Bearer {JWT}
  X-Tenant-ID: 1
```

#### 기간 지정 요청 (ISO 8601 형식 - 권장)
```bash
GET /api/admin/monitoring/visitors?page=1&size=10&from=2026-01-01T00:00:00&to=2026-01-31T23:59:59
Headers:
  Authorization: Bearer {JWT}
  X-Tenant-ID: 1
```

#### 기간 지정 요청 (공백 형식 - URL 인코딩 필요)
```bash
GET /api/admin/monitoring/visitors?page=1&size=10&from=2026-01-01%2000:00:00&to=2026-01-31%2023:59:59
Headers:
  Authorization: Bearer {JWT}
  X-Tenant-ID: 1
```

#### 키워드 검색 요청
```bash
GET /api/admin/monitoring/visitors?page=1&size=10&keyword=visitor_123
Headers:
  Authorization: Bearer {JWT}
  X-Tenant-ID: 1
```

### 응답 형식

#### 성공 응답 (200 OK)
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "content": [
      {
        "visitorId": "visitor_TW96aWxsYS81LjAg",
        "firstSeenAt": "2026-01-01T10:00:00",
        "lastSeenAt": "2026-01-20T15:30:00",
        "pageViewCount": 45,
        "eventCount": 12,
        "lastPath": "/admin/monitoring",
        "lastDevice": "desktop",
        "lastUserId": 1
      },
      {
        "visitorId": "anonymous",
        "firstSeenAt": "2026-01-15T09:00:00",
        "lastSeenAt": "2026-01-20T14:20:00",
        "pageViewCount": 8,
        "eventCount": 3,
        "lastPath": "/sign-in",
        "lastDevice": "mobile",
        "lastUserId": null
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 10,
      "sort": {
        "sorted": false
      }
    },
    "totalElements": 2,
    "totalPages": 1,
    "last": true,
    "size": 10,
    "number": 0,
    "first": true,
    "numberOfElements": 2,
    "empty": false
  },
  "timestamp": "2026-01-20T10:53:03.490",
  "success": true
}
```

#### 응답 필드 설명

**data.content[] (VisitorSummary)**
- `visitorId` (string): 방문자 ID (null인 경우 "anonymous")
- `firstSeenAt` (string, ISO 8601): 첫 방문 일시
- `lastSeenAt` (string, ISO 8601): 마지막 방문 일시
- `pageViewCount` (number): 페이지뷰 수
- `eventCount` (number): 이벤트 수
- `lastPath` (string): 마지막 방문 경로
- `lastDevice` (string, nullable): 마지막 방문 디바이스 정보
- `lastUserId` (number, nullable): 마지막 방문 사용자 ID

**페이징 정보**
- `totalElements` (number): 전체 항목 수
- `totalPages` (number): 전체 페이지 수
- `number` (number): 현재 페이지 번호 (0부터 시작)
- `size` (number): 페이지당 항목 수
- `first` (boolean): 첫 페이지 여부
- `last` (boolean): 마지막 페이지 여부

### 에러 응답

#### 400 Bad Request (파라미터 오류)
```json
{
  "status": "ERROR",
  "message": "파라미터 형식이 올바르지 않습니다.",
  "errorCode": "E2001",
  "timestamp": "2026-01-20T10:53:03.490",
  "success": false
}
```

#### 401 Unauthorized (인증 실패)
```json
{
  "status": "ERROR",
  "message": "인증이 필요합니다.",
  "errorCode": "E2000",
  "timestamp": "2026-01-20T10:53:03.490",
  "success": false
}
```

#### 500 Internal Server Error (서버 오류)
```json
{
  "status": "ERROR",
  "message": "내부 서버 오류가 발생했습니다.",
  "errorCode": "E1000",
  "timestamp": "2026-01-20T10:53:03.490",
  "success": false
}
```

---

## 2. 이벤트 로그 목록 조회 API

### 엔드포인트
```
GET /api/admin/monitoring/events
```

### 인증
- **필수**: JWT 토큰 (`Authorization: Bearer {JWT}`)
- **필수**: `X-Tenant-ID` 헤더

### 요청 파라미터

| 파라미터 | 타입 | 필수 | 기본값 | 설명 | 예시 |
|---------|------|------|--------|------|------|
| `page` | integer | 아니오 | `1` | 페이지 번호 (1부터 시작) | `1` |
| `size` | integer | 아니오 | `10` | 페이지당 항목 수 | `10` |
| `from` | string (ISO 8601) | 아니오 | 현재 시간 - 30일 | 시작 일시 | `2026-01-01T00:00:00` |
| `to` | string (ISO 8601) | 아니오 | 현재 시간 | 종료 일시 | `2026-01-31T23:59:59` |
| `eventType` | string | 아니오 | - | 이벤트 타입 필터 | `CLICK`, `VIEW`, `SUBMIT` |
| `resourceKey` | string | 아니오 | - | 리소스 키 필터 | `menu.admin.users` |
| `keyword` | string | 아니오 | - | 검색 키워드 (action, label, path) | `button` |

### 요청 예시

#### 기본 요청 (최근 30일)
```bash
GET /api/admin/monitoring/events?page=1&size=10
Headers:
  Authorization: Bearer {JWT}
  X-Tenant-ID: 1
```

#### 기간 및 필터 지정 요청 (ISO 8601 형식 - 권장)
```bash
GET /api/admin/monitoring/events?page=1&size=10&from=2026-01-01T00:00:00&to=2026-01-31T23:59:59&eventType=CLICK&resourceKey=menu.admin.users
Headers:
  Authorization: Bearer {JWT}
  X-Tenant-ID: 1
```

#### 기간 및 필터 지정 요청 (공백 형식 - URL 인코딩 필요)
```bash
GET /api/admin/monitoring/events?page=1&size=10&from=2026-01-01%2000:00:00&to=2026-01-31%2023:59:59&eventType=CLICK&resourceKey=menu.admin.users
Headers:
  Authorization: Bearer {JWT}
  X-Tenant-ID: 1
```

#### 키워드 검색 요청
```bash
GET /api/admin/monitoring/events?page=1&size=10&keyword=button
Headers:
  Authorization: Bearer {JWT}
  X-Tenant-ID: 1
```

### 응답 형식

#### 성공 응답 (200 OK)
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "content": [
      {
        "sysEventLogId": 1,
        "occurredAt": "2026-01-20T10:30:00",
        "eventType": "CLICK",
        "resourceKey": "menu.admin.users",
        "action": "click",
        "label": "사용자 관리 버튼",
        "visitorId": "visitor_TW96aWxsYS81LjAg",
        "userId": 1,
        "path": "/admin/users",
        "metadata": {
          "buttonId": "btn-user-list",
          "section": "admin"
        }
      },
      {
        "sysEventLogId": 2,
        "occurredAt": "2026-01-20T10:25:00",
        "eventType": "VIEW",
        "resourceKey": "menu.admin.monitoring",
        "action": "view",
        "label": "모니터링 페이지",
        "visitorId": "visitor_TW96aWxsYS81LjAg",
        "userId": 1,
        "path": "/admin/monitoring",
        "metadata": {
          "pageTitle": "모니터링 대시보드"
        }
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 10,
      "sort": {
        "sorted": false
      }
    },
    "totalElements": 2,
    "totalPages": 1,
    "last": true,
    "size": 10,
    "number": 0,
    "first": true,
    "numberOfElements": 2,
    "empty": false
  },
  "timestamp": "2026-01-20T10:53:03.490",
  "success": true
}
```

#### 응답 필드 설명

**data.content[] (EventLogItem)**
- `sysEventLogId` (number): 이벤트 로그 ID
- `occurredAt` (string, ISO 8601): 이벤트 발생 일시
- `eventType` (string): 이벤트 타입 (`CLICK`, `VIEW`, `SUBMIT`, `CHANGE`, 등)
- `resourceKey` (string): 리소스 키 (예: `menu.admin.users`)
- `action` (string): 액션 (예: `click`, `view`, `submit`)
- `label` (string): 라벨/설명
- `visitorId` (string, nullable): 방문자 ID
- `userId` (number, nullable): 사용자 ID
- `path` (string): 이벤트 발생 경로
- `metadata` (object, nullable): 추가 메타데이터 (JSON 객체)

**페이징 정보**
- `totalElements` (number): 전체 항목 수
- `totalPages` (number): 전체 페이지 수
- `number` (number): 현재 페이지 번호 (0부터 시작)
- `size` (number): 페이지당 항목 수
- `first` (boolean): 첫 페이지 여부
- `last` (boolean): 마지막 페이지 여부

### 에러 응답

#### 400 Bad Request (파라미터 오류)
```json
{
  "status": "ERROR",
  "message": "파라미터 형식이 올바르지 않습니다.",
  "errorCode": "E2001",
  "timestamp": "2026-01-20T10:53:03.490",
  "success": false
}
```

#### 401 Unauthorized (인증 실패)
```json
{
  "status": "ERROR",
  "message": "인증이 필요합니다.",
  "errorCode": "E2000",
  "timestamp": "2026-01-20T10:53:03.490",
  "success": false
}
```

#### 500 Internal Server Error (서버 오류)
```json
{
  "status": "ERROR",
  "message": "내부 서버 오류가 발생했습니다.",
  "errorCode": "E1000",
  "timestamp": "2026-01-20T10:53:03.490",
  "success": false
}
```

---

## 프론트엔드 구현 가이드

### 1. 날짜/시간 형식

#### 📌 중요: DB 저장 형식 vs API 요청 형식

**DB에 저장되는 형식**과 **API 요청 형식**은 다릅니다:

- **DB 저장 형식**: `2026-01-19 13:33:50.621183` (PostgreSQL TIMESTAMP 표시 형식)
- **API 요청 형식**: `2026-01-19T13:33:50` (ISO 8601 형식) ⭐ **이 형식을 사용하세요**

#### 지원되는 형식

1. **ISO 8601 형식 (권장)** ⭐
   - 형식: `YYYY-MM-DDTHH:mm:ss`
   - 예시: `2026-01-20T10:30:00`
   - 장점: URL 인코딩 불필요, 국제 표준, JavaScript `Date` 객체와 호환

2. **공백 형식 (대체)**
   - 형식: `YYYY-MM-DD HH:mm:ss`
   - 예시: `2026-01-20 10:30:00`
   - 주의: URL 인코딩 필요 (공백 → `%20` 또는 `+`)
   - 예시: `from=2026-01-20%2010:30:00` 또는 `from=2026-01-20+10:30:00`

#### 타임존 처리
- 타임존 정보 없이 전송 시 서버 로컬 시간으로 처리됩니다
- 필요시 타임존 포함: `2026-01-20T10:30:00+09:00` (KST)

#### 기본값
- `from`과 `to`를 생략하면 자동으로 **최근 30일** 데이터를 조회합니다

### 2. 페이징 처리
- **페이지 번호**: 1부터 시작 (프론트엔드 기준)
- **페이지 크기**: 기본값 10, 최대 100 권장
- **응답의 `number` 필드**: 0부터 시작 (백엔드 기준)

### 3. 필터링
- **Visitors API**: `keyword`로 visitorId 또는 path 검색
- **Events API**: `eventType`, `resourceKey`, `keyword` 조합 가능

### 4. 에러 처리
- **401**: JWT 토큰 만료 또는 없음 → 로그인 페이지로 리다이렉트
- **400**: 파라미터 오류 → 사용자에게 오류 메시지 표시
- **500**: 서버 오류 → 재시도 또는 관리자에게 문의 안내

### 5. 예시 코드 (TypeScript/Angular)

```typescript
// Visitors API 호출 예시
async getVisitors(params: {
  page?: number;
  size?: number;
  from?: string | Date;
  to?: string | Date;
  keyword?: string;
}) {
  const queryParams = new URLSearchParams();
  if (params.page) queryParams.set('page', params.page.toString());
  if (params.size) queryParams.set('size', params.size.toString());
  
  // 날짜 형식 변환 (Date 객체 또는 ISO 8601 문자열)
  if (params.from) {
    const fromStr = params.from instanceof Date 
      ? params.from.toISOString().slice(0, 19) // '2026-01-01T00:00:00'
      : params.from;
    queryParams.set('from', fromStr);
  }
  if (params.to) {
    const toStr = params.to instanceof Date 
      ? params.to.toISOString().slice(0, 19) // '2026-01-31T23:59:59'
      : params.to;
    queryParams.set('to', toStr);
  }
  
  if (params.keyword) queryParams.set('keyword', params.keyword);

  return this.http.get<ApiResponse<Page<VisitorSummary>>>(
    `/api/admin/monitoring/visitors?${queryParams.toString()}`,
    {
      headers: {
        'Authorization': `Bearer ${this.getToken()}`,
        'X-Tenant-ID': this.getTenantId()
      }
    }
  );
}

// 사용 예시
// 1. Date 객체 사용 (권장)
this.getVisitors({
  page: 1,
  size: 10,
  from: new Date('2026-01-01'),
  to: new Date('2026-01-31')
});

// 2. ISO 8601 문자열 사용
this.getVisitors({
  page: 1,
  size: 10,
  from: '2026-01-01T00:00:00',
  to: '2026-01-31T23:59:59'
});

// 3. 공백 형식 문자열 사용 (URL 인코딩 자동 처리)
this.getVisitors({
  page: 1,
  size: 10,
  from: '2026-01-01 00:00:00', // URLSearchParams가 자동으로 인코딩
  to: '2026-01-31 23:59:59'
});
```

// Events API 호출 예시
async getEvents(params: {
  page?: number;
  size?: number;
  from?: string;
  to?: string;
  eventType?: string;
  resourceKey?: string;
  keyword?: string;
}) {
  const queryParams = new URLSearchParams();
  if (params.page) queryParams.set('page', params.page.toString());
  if (params.size) queryParams.set('size', params.size.toString());
  if (params.from) queryParams.set('from', params.from);
  if (params.to) queryParams.set('to', params.to);
  if (params.eventType) queryParams.set('eventType', params.eventType);
  if (params.resourceKey) queryParams.set('resourceKey', params.resourceKey);
  if (params.keyword) queryParams.set('keyword', params.keyword);

  return this.http.get<ApiResponse<Page<EventLogItem>>>(
    `/api/admin/monitoring/events?${queryParams.toString()}`,
    {
      headers: {
        'Authorization': `Bearer ${this.getToken()}`,
        'X-Tenant-ID': this.getTenantId()
      }
    }
  );
}
```

---

## 주의사항

1. **날짜 형식** ⭐ **중요**
   - **API 요청 형식**: ISO 8601 형식 `YYYY-MM-DDTHH:mm:ss` 사용 (예: `2026-01-20T10:30:00`)
   - **DB 저장 형식과 다름**: 
     - DB에는 `2026-01-19 13:33:50.621183` 형식으로 저장되지만
     - API 요청은 `2026-01-19T13:33:50` 형식을 사용해야 합니다
   - **이유**: Spring의 `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)`이 ISO 8601 형식을 기대합니다
   - **대체 형식**: `YYYY-MM-DD HH:mm:ss` 형식도 지원하지만 URL 인코딩 필요 (공백 → `%20`)
2. **페이징**: 페이지 번호는 1부터 시작하지만, 백엔드 응답의 `number`는 0부터 시작합니다.
3. **기본값**: `from`과 `to`를 생략하면 자동으로 최근 30일 데이터를 조회합니다.
4. **인증**: 모든 요청에 JWT 토큰과 `X-Tenant-ID` 헤더가 필수입니다.
5. **타임존**: 타임존 정보 없이 전송 시 서버 로컬 시간으로 처리됩니다.
6. **URL 인코딩**: 공백(` `)이 포함된 날짜 형식 사용 시 `URLSearchParams`가 자동으로 인코딩하므로 걱정하지 않아도 됩니다.
