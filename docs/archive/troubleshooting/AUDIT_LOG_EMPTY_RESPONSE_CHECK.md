# 감사 로그 API 빈 응답 확인 가이드

## 문제 상황

`GET /api/admin/audit-logs` API를 호출했을 때 빈 응답(`items: []`)이 반환되는 경우 확인 방법

## 확인 사항

### 1. 데이터베이스에 감사 로그가 있는지 확인

```sql
-- 전체 감사 로그 수 확인
SELECT COUNT(*) FROM com_audit_logs WHERE tenant_id = 1;

-- 최근 감사 로그 확인
SELECT audit_log_id, tenant_id, action, resource_type, created_at 
FROM com_audit_logs 
WHERE tenant_id = 1 
ORDER BY created_at DESC 
LIMIT 10;

-- 특정 기간의 감사 로그 확인
SELECT audit_log_id, tenant_id, action, resource_type, created_at 
FROM com_audit_logs 
WHERE tenant_id = 1 
  AND created_at >= '2026-01-14 18:53:00'  -- KST 변환 후 시간
  AND created_at <= '2026-01-21 18:53:00'  -- KST 변환 후 시간
ORDER BY created_at DESC;
```

### 2. 날짜 변환 확인

**프론트엔드에서 보낸 시간**: `2026-01-14T09:53:00` (UTC로 간주)

**컨트롤러에서 변환**:
- `convertUtcToKst()` 함수가 UTC를 KST(UTC+9)로 변환
- `2026-01-14T09:53:00` (UTC) → `2026-01-14T18:53:00` (KST)

**데이터베이스 쿼리**:
- KST 시간을 `yyyy-MM-dd HH:mm:ss` 형식으로 변환
- `2026-01-14 18:53:00` ~ `2026-01-21 18:53:00`

### 3. 감사 로그가 생성되는 시점 확인

감사 로그는 다음 작업 시 자동으로 생성됩니다:

- **Role 관련**:
  - `ROLE_CREATE`: 역할 생성 시
  - `ROLE_UPDATE`: 역할 수정 시
  - `ROLE_DELETE`: 역할 삭제 시
  - `ROLE_MEMBER_ADD`: 역할 멤버 추가 시
  - `ROLE_MEMBER_REMOVE`: 역할 멤버 삭제 시
  - `ROLE_MEMBER_UPDATE`: 역할 멤버 Bulk 업데이트 시
  - `ROLE_PERMISSION_BULK_UPDATE`: 역할 권한 Bulk 업데이트 시

- **User 관련**:
  - `USER_CREATE`: 사용자 생성 시
  - `USER_UPDATE`: 사용자 수정 시
  - `USER_DELETE`: 사용자 삭제 시
  - `USER_PASSWORD_RESET`: 비밀번호 재설정 시

- **기타**:
  - `RESOURCE_CREATE`, `RESOURCE_UPDATE`, `RESOURCE_DELETE`
  - `CODE_CREATE`, `CODE_UPDATE`, `CODE_DELETE`
  - `MENU_REORDER`: 메뉴 순서 변경 시

### 4. 테스트 방법

#### 방법 1: 역할 생성/수정으로 감사 로그 생성

```bash
# 역할 생성 (감사 로그 생성)
POST http://localhost:8080/api/admin/roles
Headers:
  Authorization: Bearer {JWT}
  X-Tenant-ID: 1
Body:
{
  "roleCode": "TEST_ROLE",
  "roleName": "테스트 역할",
  "description": "테스트용"
}

# 감사 로그 조회
GET http://localhost:8080/api/admin/audit-logs?page=1&size=20
Headers:
  Authorization: Bearer {JWT}
  X-Tenant-ID: 1
```

#### 방법 2: 날짜 범위를 넓게 설정

```bash
# 최근 30일 데이터 조회
GET http://localhost:8080/api/admin/audit-logs?page=1&size=20&from=2025-12-01T00%3A00%3A00&to=2026-01-31T23%3A59%3A59
```

#### 방법 3: 필터 없이 전체 조회

```bash
# 날짜 필터 없이 전체 조회
GET http://localhost:8080/api/admin/audit-logs?page=1&size=20
```

### 5. 로그 확인

애플리케이션 로그에서 다음 메시지를 확인:

```
DEBUG ... AdminAuditLogController : getAuditLogs 호출: tenantId=1, from=2026-01-14T09:53:00, to=2026-01-21T09:53:00
DEBUG ... AdminAuditLogController : getAuditLogs 파라미터 적용: tenantId=1, fromDateTime=2026-01-14T18:53:00, toDateTime=2026-01-21T18:53:00
DEBUG ... AuditLogQueryService : AuditLogQueryService.getAuditLogs: tenantId=1, fromStr=2026-01-14 18:53:00, toStr=2026-01-21 18:53:00
DEBUG ... AuditLogQueryService : AuditLogQueryService.getAuditLogs 결과: totalElements=0, totalPages=0, contentSize=0
```

### 6. 응답 구조 확인

빈 응답이 정상인 경우:

```json
{
  "success": true,
  "data": {
    "items": [],
    "page": 1,
    "size": 20,
    "totalItems": 0,
    "totalPages": 0
  }
}
```

이것은 **정상적인 응답**입니다. 해당 기간에 감사 로그가 없으면 빈 배열이 반환됩니다.

## 해결 방법

### 감사 로그가 없는 경우

1. **역할/사용자 생성/수정 작업 수행**: 감사 로그가 생성되도록 작업 수행
2. **날짜 범위 확대**: 더 넓은 날짜 범위로 조회
3. **필터 제거**: 날짜 필터 없이 전체 조회

### 감사 로그가 있는데 조회되지 않는 경우

1. **날짜 변환 확인**: UTC → KST 변환이 올바른지 확인
2. **tenant_id 확인**: 요청한 tenant_id와 데이터베이스의 tenant_id가 일치하는지 확인
3. **SQL 로그 확인**: 실제 실행된 SQL 쿼리와 파라미터 확인

## 참고

- 감사 로그는 `@Async`로 비동기 저장되므로, 작업 직후 즉시 조회되지 않을 수 있습니다 (일반적으로 1초 이내 저장됨)
- 감사 로그 저장 실패는 Silent Fail 정책으로 메인 로직에 영향을 주지 않습니다
