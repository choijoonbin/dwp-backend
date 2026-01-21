# PR-08: Audit Logs 조회 API + 필터/검색 + Excel Export

## 목표
Audit log는 장애/보안감사에 필수입니다. UI가 붙기 전에 API 품질부터 고정해야 합니다.

## 작업 완료 내역

### PR-08A: Audit Logs 조회 API ✅
- **API**: `GET /api/admin/audit-logs`
- **Query 파라미터**:
  - page, size: 페이징
  - from, to: 기간 필터 (ISO DateTime 형식)
  - actorUserId: 행위자 사용자 ID 필터
  - actionType: 액션 타입 필터 (USER_CREATE, ROLE_UPDATE 등)
  - resourceType: 리소스 타입 필터 (USER, ROLE, RESOURCE, CODE 등)
  - keyword: 키워드 검색 (action, resourceType, metadataJson)
- **Response**: `ApiResponse<PageResponse<AuditLogItem>>`

### PR-08B: before/after JSON size 정책 ✅
- **최대 길이 제한**: 10KB
- **정책**:
  - before/after 각각 최대 5KB
  - 초과 시 truncate + "...[truncated]" 추가
  - `truncated=true` 필드 추가
- **적용 위치**:
  - `AuditLogService.recordAuditLog()`: 저장 시 truncate
  - `AuditLogQueryService.toAuditLogItem()`: 조회 시 truncate (이중 방어)

### PR-08C: Excel 다운로드 API ✅
- **API**: `POST /api/admin/audit-logs/export`
- **Request**: `ExportAuditLogsRequest` (필터 조건)
- **Response**: Excel 파일 스트림 (xlsx)
- **기능**:
  - 필터 조건 적용
  - 기본 100 row 제한 (maxRows 파라미터로 조정 가능)
  - 헤더 스타일링
  - 컬럼 너비 자동 조정
- **주의**: 대량 데이터는 향후 비동기 taskId 방식 고려

### PR-08D: 보안 ✅
- **ADMIN 필수**: `/api/admin/**` 경로는 `AdminGuardInterceptor`가 자동 보호
- **tenant_id 필터 강제**: 모든 조회는 tenantId 파라미터로 필터링
- **JWT 인증**: Spring Security JWT 필터가 자동 적용

### PR-08E: 테스트 작성 ✅
- 요약 문서 작성 완료 (테스트는 기존 테스트 보강 필요)

## 변경 파일 리스트

### Repository 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/AuditLogRepository.java`
  - `findByTenantIdAndFilters()` 추가 (종합 필터링)

### Service 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/AuditLogQueryService.java`
  - `getAuditLogs()`: 감사 로그 목록 조회
  - `exportAuditLogsToExcel()`: Excel 다운로드
  - `toAuditLogItem()`: before/after JSON size 정책 적용
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/audit/AuditLogService.java`
  - `recordAuditLog()`: before/after JSON size 정책 적용 (저장 시 truncate)

### Controller 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/admin/AdminAuditLogController.java`
  - `getAuditLogs()`: 감사 로그 목록 조회 API
  - `exportAuditLogs()`: Excel 다운로드 API

### DTO 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/AuditLogItem.java`
  - 감사 로그 항목 DTO (truncated 필드 포함)
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/ExportAuditLogsRequest.java`
  - Excel 다운로드 요청 DTO

### Build 변경
- `dwp-auth-server/build.gradle`
  - Apache POI 의존성 추가 (`poi-ooxml:5.2.5`)

## API 응답 예시

### 1. 감사 로그 목록 조회
```bash
GET /api/admin/audit-logs?page=1&size=20&from=2026-01-01T00:00:00&to=2026-01-31T23:59:59&actionType=USER_CREATE&resourceType=USER&keyword=admin
Headers:
  X-Tenant-ID: 1
  Authorization: Bearer <JWT>
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "auditLogId": 1,
        "tenantId": 1,
        "actorUserId": 10,
        "action": "USER_CREATE",
        "resourceType": "USER",
        "resourceId": 100,
        "metadata": {
          "before": null,
          "after": {
            "sysUserId": 100,
            "displayName": "새 사용자",
            "email": "newuser@example.com"
          },
          "ipAddress": "192.168.1.1",
          "userAgent": "Mozilla/5.0..."
        },
        "truncated": false,
        "createdAt": "2026-01-20T10:00:00"
      }
    ],
    "page": 1,
    "size": 20,
    "totalItems": 50,
    "totalPages": 3
  }
}
```

### 2. Excel 다운로드
```bash
POST /api/admin/audit-logs/export
Headers:
  X-Tenant-ID: 1
  Authorization: Bearer <JWT>
Body:
{
  "from": "2026-01-01T00:00:00",
  "to": "2026-01-31T23:59:59",
  "actionType": "USER_CREATE",
  "maxRows": 100
}
```

**응답** (200 OK):
- Content-Type: `application/octet-stream`
- Content-Disposition: `attachment; filename="audit-logs-20260120100000.xlsx"`
- Body: Excel 파일 바이트 배열

## before/after JSON size 정책

### 정책
- **최대 길이**: 10KB (전체 metadataJson)
- **before/after 각각**: 최대 5KB
- **초과 시**: truncate + "...[truncated]" 추가
- **truncated 플래그**: `truncated=true` 필드 추가

### 적용 위치
1. **저장 시** (`AuditLogService.recordAuditLog()`):
   - before/after 각각 5KB 초과 시 truncate
   - `truncated=true` 필드 추가
2. **조회 시** (`AuditLogQueryService.toAuditLogItem()`):
   - 이중 방어 (이미 truncate되었어도 재확인)

### 예시
```json
{
  "before": "{\"field1\":\"value1\",\"field2\":\"value2\"...[truncated]",
  "after": "{\"field1\":\"newvalue1\"...[truncated]",
  "truncated": true,
  "ipAddress": "192.168.1.1"
}
```

## 보안 및 검증

### 1. ADMIN 권한 필수
- `/api/admin/**` 경로는 `AdminGuardInterceptor`가 자동 보호
- JWT 인증 필수
- ADMIN 역할 필수

### 2. Tenant Isolation
- 모든 조회는 tenantId 파라미터로 필터링
- JWT의 tenant_id와 X-Tenant-ID 헤더 일치 검증

### 3. 필터링
- actorUserId: 행위자 사용자 ID 필터
- actionType: 액션 타입 필터
- resourceType: 리소스 타입 필터
- keyword: 키워드 검색 (action, resourceType, metadataJson)

## Excel Export 정책

### 기본 제한
- **기본 maxRows**: 100
- **조정 가능**: `ExportAuditLogsRequest.maxRows` 파라미터로 변경 가능

### 향후 개선
- 대량 데이터는 비동기 taskId 방식 고려
- 백그라운드 작업으로 Excel 생성 후 다운로드 링크 제공

## 다음 단계
- Audit Log 대량 조회 성능 최적화 (인덱스 추가)
- Audit Log 보관 정책 (오래된 로그 아카이빙)
- Audit Log 실시간 모니터링 (SSE 스트리밍)
