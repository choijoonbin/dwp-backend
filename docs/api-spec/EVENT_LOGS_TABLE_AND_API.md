# 이벤트 로그 테이블 및 API 가이드

**작성일**: 2026-01-20  
**목적**: Events API가 사용하는 테이블과 이벤트 적재 API 정보 제공

---

## 📊 Events API가 바라보는 테이블

### 테이블명: `sys_event_logs`

**마이그레이션 파일**: `V11__create_event_logs.sql`

**테이블 구조**:

| 컬럼명 | 타입 | 설명 |
|--------|------|------|
| `sys_event_log_id` | BIGSERIAL (PK) | 이벤트 로그 식별자 |
| `tenant_id` | BIGINT (NOT NULL) | 테넌트 식별자 |
| `occurred_at` | TIMESTAMP (NOT NULL) | 이벤트 발생 시간 (기본값: CURRENT_TIMESTAMP) |
| `event_type` | VARCHAR(50) (NOT NULL) | 이벤트 타입 (view/click/execute 등) |
| `resource_key` | VARCHAR(255) (NOT NULL) | 리소스 키 (menu.xxx / btn.xxx 등) |
| `action` | VARCHAR(100) (NOT NULL) | 액션 (view_users / click_send 등) |
| `label` | VARCHAR(200) | UI 표시용 라벨 |
| `visitor_id` | VARCHAR(255) | 방문자 식별자 |
| `user_id` | BIGINT | 로그인 사용자 ID |
| `path` | VARCHAR(500) | 경로 (/admin/users 등) |
| `metadata` | JSONB | 추가 데이터 (JSON) |
| `ip_address` | VARCHAR(50) | 접속 IP |
| `user_agent` | TEXT | User-Agent |
| `created_at` | TIMESTAMP (NOT NULL) | 생성일시 |
| `created_by` | BIGINT | 생성자 user_id |
| `updated_at` | TIMESTAMP (NOT NULL) | 수정일시 |
| `updated_by` | BIGINT | 수정자 user_id |

**인덱스**:
- `idx_sys_event_logs_tenant_occurred`: (tenant_id, occurred_at DESC)
- `idx_sys_event_logs_tenant_visitor`: (tenant_id, visitor_id)
- `idx_sys_event_logs_tenant_resource`: (tenant_id, resource_key)
- `idx_sys_event_logs_tenant_user`: (tenant_id, user_id)
- `idx_sys_event_logs_event_type`: (event_type)

---

## 🔌 이벤트 적재 API

### API 엔드포인트

**`POST /api/monitoring/event`**

### 설명

프론트엔드에서 사용자 액션(클릭, 실행 등)을 수집하는 API입니다.

### 인증

- **인증**: 선택적 (JWT 토큰이 있으면 userId 자동 추출)
- **헤더**: `X-Tenant-ID` (필수)

### 요청 형식

**헤더**:
```
Content-Type: application/json
X-Tenant-ID: {tenantId}
Authorization: Bearer {JWT_TOKEN} (선택)
```

**Request Body** (`EventCollectRequest`):
```json
{
  "eventType": "click",                    // 필수: 이벤트 타입 (view/click/execute 등)
  "resourceKey": "menu.admin.users",       // 필수: 리소스 키
  "action": "click_users_list",            // 필수: 액션
  "label": "사용자 목록 조회",              // 선택: UI 표시용 라벨
  "visitorId": "visitor_123",              // 선택: 방문자 식별자
  "path": "/admin/users",                  // 선택: 경로
  "metadata": {                            // 선택: 추가 데이터
    "buttonId": "btn-users-list",
    "timestamp": "2026-01-20T13:22:00"
  }
}
```

### 필수 필드

- `eventType`: 이벤트 타입 (예: `click`, `view`, `execute`)
- `resourceKey`: 리소스 키 (예: `menu.admin.users`, `btn.send`)
- `action`: 액션 (예: `click_users_list`, `view_dashboard`)

### 선택 필드

- `label`: UI 표시용 라벨
- `visitorId`: 방문자 식별자
- `path`: 경로
- `metadata`: 추가 데이터 (JSON 객체)

### 응답 형식

**성공 (200 OK)**:
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "accepted": true
  },
  "timestamp": "2026-01-20T13:22:00.000",
  "success": true
}
```

**에러 (400 Bad Request)**:
```json
{
  "status": "ERROR",
  "message": "eventType은 필수입니다",
  "errorCode": "E2001",
  "timestamp": "2026-01-20T13:22:00.000",
  "success": false
}
```

---

## 📝 사용 예시

### 예시 1: 버튼 클릭 이벤트

```bash
curl -X POST "http://localhost:8080/api/monitoring/event" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: 1" \
  -d '{
    "eventType": "click",
    "resourceKey": "menu.admin.users",
    "action": "click_users_list",
    "label": "사용자 목록 조회",
    "visitorId": "visitor_123",
    "path": "/admin/users",
    "metadata": {
      "buttonId": "btn-users-list"
    }
  }'
```

### 예시 2: 페이지 뷰 이벤트

```bash
curl -X POST "http://localhost:8080/api/monitoring/event" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: 1" \
  -d '{
    "eventType": "view",
    "resourceKey": "menu.admin.dashboard",
    "action": "view_dashboard",
    "label": "대시보드 조회",
    "visitorId": "visitor_123",
    "path": "/admin/dashboard"
  }'
```

### 예시 3: 실행 이벤트

```bash
curl -X POST "http://localhost:8080/api/monitoring/event" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -d '{
    "eventType": "execute",
    "resourceKey": "btn.send",
    "action": "send_email",
    "label": "이메일 전송",
    "path": "/admin/users",
    "metadata": {
      "recipient": "user@example.com",
      "subject": "Welcome"
    }
  }'
```

---

## 🔍 데이터 흐름

```
프론트엔드
  ↓
POST /api/monitoring/event
  ↓
MonitoringCollectController.recordEvent()
  ↓
MonitoringCollectService.recordEvent()
  ↓
EventLogRepository.save()
  ↓
sys_event_logs 테이블에 저장
  ↓
GET /api/admin/monitoring/events
  ↓
AdminMonitoringController.getEvents()
  ↓
AdminMonitoringService.getEvents()
  ↓
EventLogRepository.findByTenantIdAndFilters()
  ↓
sys_event_logs 테이블에서 조회
```

---

## ⚠️ 주의사항

1. **X-Tenant-ID 헤더 필수**: 이벤트 수집 시 반드시 `X-Tenant-ID` 헤더를 포함해야 합니다.
2. **필수 필드**: `eventType`, `resourceKey`, `action`은 필수입니다.
3. **문자열 길이 제한**: 
   - `eventType`: 최대 50자
   - `resourceKey`: 최대 255자
   - `action`: 최대 100자
   - `label`: 최대 200자
   - `visitorId`: 최대 255자
   - `path`: 최대 500자
4. **Silent Fail 정책**: 이벤트 수집 실패가 프론트엔드에 영향을 주지 않도록 try-catch로 처리됩니다.
5. **인증 선택적**: JWT 토큰이 없어도 수집 가능하지만, `X-Tenant-ID` 헤더는 필수입니다.

---

## 📊 Events 조회 API

### API 엔드포인트

**`GET /api/admin/monitoring/events`**

### 파라미터

- `page`: 페이지 번호 (1-based, 기본값: 1)
- `size`: 페이지당 항목 수 (기본값: 10)
- `from`: 시작 날짜 (ISO 8601, 기본값: 30일 전)
- `to`: 종료 날짜 (ISO 8601, 기본값: 현재)
- `keyword`: 검색 키워드 (선택)
- `eventType`: 이벤트 타입 필터 (선택)
- `resourceKey`: 리소스 키 필터 (선택)

### 응답 형식

```json
{
  "status": "SUCCESS",
  "data": {
    "content": [
      {
        "sysEventLogId": 1,
        "occurredAt": "2026-01-20T13:22:00",
        "eventType": "click",
        "resourceKey": "menu.admin.users",
        "action": "click_users_list",
        "label": "사용자 목록 조회",
        "visitorId": "visitor_123",
        "userId": 1,
        "path": "/admin/users",
        "metadata": {
          "buttonId": "btn-users-list"
        }
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "number": 0,
    "size": 10
  }
}
```

---

## 🔗 관련 파일

- **엔티티**: `dwp-auth-server/src/main/java/com/dwp/services/auth/entity/monitoring/EventLog.java`
- **Repository**: `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/monitoring/EventLogRepository.java`
- **수집 컨트롤러**: `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/monitoring/MonitoringCollectController.java`
- **수집 서비스**: `dwp-auth-server/src/main/java/com/dwp/services/auth/service/monitoring/MonitoringCollectService.java`
- **조회 컨트롤러**: `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/admin/monitoring/AdminMonitoringController.java`
- **조회 서비스**: `dwp-auth-server/src/main/java/com/dwp/services/auth/service/monitoring/AdminMonitoringService.java`
- **마이그레이션**: `dwp-auth-server/src/main/resources/db/migration/V11__create_event_logs.sql`
- **DTO**: `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/monitoring/EventCollectRequest.java`

---

**문서 작성일**: 2026-01-20  
**작성자**: DWP Backend Team
