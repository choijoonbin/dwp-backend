# 모니터링 API 프론트엔드 요구사항 vs 실제 구현 비교

**작성일**: 2026-01-20  
**목적**: 프론트엔드 요구사항과 백엔드 구현 간 차이점 확인 및 개선 방안 제시

---

## 📊 비교 결과 요약

| 탭 | API 엔드포인트 | 상태 | 누락된 파라미터 |
|---|---------------|------|----------------|
| 1. 페이지뷰 | `/api/admin/monitoring/page-views` | ❌ **불일치** | `from`, `to`, `keyword`, `route`, `menu`, `path`, `userId` |
| 2. 방문자뷰 | `/api/admin/monitoring/visitors` | ✅ **일치** | 없음 |
| 3. 이벤트 | `/api/admin/monitoring/events` | ✅ **일치** | 없음 |
| 4. API 히스토리 | `/api/admin/monitoring/api-histories` | ❌ **불일치** | `from`, `to`, `keyword`, `apiName`, `apiUrl`, `statusCode`, `userId` |

---

## 1. 페이지뷰 탭 (activeTab === 0)

### 프론트엔드 요구사항

**API**: `GET /api/admin/monitoring/page-views`

**파라미터**:
- `page`: 페이지 번호 (1-based)
- `size`: 페이지당 항목 수
- `from`: 시작 날짜 (ISO 8601)
- `to`: 종료 날짜 (ISO 8601)
- `keyword`: 검색 키워드 (선택)
- `route`: 라우트 필터 (선택)
- `menu`: 메뉴 필터 (선택)
- `path`: 경로 필터 (선택)
- `userId`: 사용자 ID 필터 (선택)

### 실제 구현

```java
@GetMapping("/page-views")
public ApiResponse<Page<PageViewEvent>> getPageViews(
        @RequestHeader("X-Tenant-ID") Long tenantId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    // from, to, keyword, route, menu, path, userId 파라미터 없음
}
```

### 차이점

❌ **누락된 파라미터**:
- `from` (시작 날짜)
- `to` (종료 날짜)
- `keyword` (검색 키워드)
- `route` (라우트 필터)
- `menu` (메뉴 필터)
- `path` (경로 필터)
- `userId` (사용자 ID 필터)

### 개선 필요

페이지뷰 API에 필터링 기능을 추가해야 합니다.

---

## 2. 방문자뷰 탭 (activeTab === 1)

### 프론트엔드 요구사항

**API**: `GET /api/admin/monitoring/visitors`

**파라미터**:
- `page`: 페이지 번호 (1-based)
- `size`: 페이지당 항목 수
- `from`: 시작 날짜 (ISO 8601, 기본값: 30일 전)
- `to`: 종료 날짜 (ISO 8601, 기본값: 현재)
- `keyword`: 검색 키워드 (선택)

### 실제 구현

```java
@GetMapping("/visitors")
public ApiResponse<Page<VisitorSummary>> getVisitors(
        @RequestHeader("X-Tenant-ID") Long tenantId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
        @RequestParam(required = false) String keyword) {
    // 모든 파라미터 지원 ✅
}
```

### 차이점

✅ **완벽히 일치**: 모든 요구 파라미터가 구현되어 있습니다.

---

## 3. 이벤트 탭 (activeTab === 2)

### 프론트엔드 요구사항

**API**: `GET /api/admin/monitoring/events`

**파라미터**:
- `page`: 페이지 번호 (1-based)
- `size`: 페이지당 항목 수
- `from`: 시작 날짜 (ISO 8601, 기본값: 30일 전)
- `to`: 종료 날짜 (ISO 8601, 기본값: 현재)
- `keyword`: 검색 키워드 (선택)
- `eventType`: 이벤트 타입 필터 (선택)
- `resourceKey`: 리소스 키 필터 (선택)

### 실제 구현

```java
@GetMapping("/events")
public ApiResponse<Page<EventLogItem>> getEvents(
        @RequestHeader("X-Tenant-ID") Long tenantId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
        @RequestParam(required = false) String eventType,
        @RequestParam(required = false) String resourceKey,
        @RequestParam(required = false) String keyword) {
    // 모든 파라미터 지원 ✅
}
```

### 차이점

✅ **완벽히 일치**: 모든 요구 파라미터가 구현되어 있습니다.

---

## 4. API 히스토리 탭 (activeTab === 3)

### 프론트엔드 요구사항

**API**: `GET /api/admin/monitoring/api-histories`

**파라미터**:
- `page`: 페이지 번호 (1-based)
- `size`: 페이지당 항목 수
- `from`: 시작 날짜 (ISO 8601)
- `to`: 종료 날짜 (ISO 8601)
- `keyword`: 검색 키워드 (선택)
- `apiName`: API 이름 필터 (선택)
- `apiUrl`: API URL 필터 (선택)
- `statusCode`: HTTP 상태 코드 필터 (선택)
- `userId`: 사용자 ID 필터 (선택)

### 실제 구현

```java
@GetMapping("/api-histories")
public ApiResponse<Page<ApiCallHistory>> getApiHistories(
        @RequestHeader("X-Tenant-ID") Long tenantId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    // from, to, keyword, apiName, apiUrl, statusCode, userId 파라미터 없음
}
```

### 차이점

❌ **누락된 파라미터**:
- `from` (시작 날짜)
- `to` (종료 날짜)
- `keyword` (검색 키워드)
- `apiName` (API 이름 필터) - `path` 컬럼과 매핑 가능
- `apiUrl` (API URL 필터) - `path` 컬럼과 매핑 가능
- `statusCode` (HTTP 상태 코드 필터)
- `userId` (사용자 ID 필터)

### 개선 필요

API 히스토리 API에 필터링 기능을 추가해야 합니다.

---

## 🔧 개선 방안

### 우선순위 1: 페이지뷰 API 필터링 추가

**필요한 파라미터**:
- `from`, `to`: 날짜 범위 필터
- `keyword`: 검색 키워드 (path, menuKey, title 등)
- `route`: 라우트 필터 (path 컬럼과 매핑)
- `menu`: 메뉴 필터 (menuKey 컬럼과 매핑)
- `path`: 경로 필터 (path 컬럼과 매핑)
- `userId`: 사용자 ID 필터

**구현 방법**:
1. `AdminMonitoringController.getPageViews()` 메서드에 파라미터 추가
2. `MonitoringService.getPageViews()` 메서드에 필터링 로직 추가
3. `PageViewEventRepository`에 필터링 쿼리 메서드 추가

### 우선순위 2: API 히스토리 API 필터링 추가

**필요한 파라미터**:
- `from`, `to`: 날짜 범위 필터
- `keyword`: 검색 키워드 (path, method 등)
- `apiName`: API 이름 필터 (path 컬럼과 매핑)
- `apiUrl`: API URL 필터 (path 컬럼과 매핑)
- `statusCode`: HTTP 상태 코드 필터
- `userId`: 사용자 ID 필터

**구현 방법**:
1. `AdminMonitoringController.getApiHistories()` 메서드에 파라미터 추가
2. `MonitoringService.getApiHistories()` 메서드에 필터링 로직 추가
3. `ApiCallHistoryRepository`에 필터링 쿼리 메서드 추가

---

## 📝 페이지 번호 차이점

### 프론트엔드 기대값
- 모든 API: `page` 파라미터가 **1-based** (1부터 시작)

### 실제 구현
- **방문자뷰, 이벤트**: `page` 파라미터가 1-based로 처리됨 ✅
  ```java
  Pageable pageable = PageRequest.of(page - 1, size); // 1-base to 0-base 변환
  ```
- **페이지뷰, API 히스토리**: `page` 파라미터가 0-based로 처리됨 ❌
  ```java
  Pageable pageable = PageRequest.of(page, size); // 0-based 그대로 사용
  ```

### 개선 필요

페이지뷰와 API 히스토리 API도 1-based로 통일해야 합니다.

---

## ✅ 결론

### 완벽히 일치하는 API
- ✅ 방문자뷰 탭 (`/api/admin/monitoring/visitors`)
- ✅ 이벤트 탭 (`/api/admin/monitoring/events`)

### 개선이 필요한 API
- ❌ 페이지뷰 탭 (`/api/admin/monitoring/page-views`)
  - 필터링 파라미터 추가 필요
  - 페이지 번호 1-based로 통일 필요
- ❌ API 히스토리 탭 (`/api/admin/monitoring/api-histories`)
  - 필터링 파라미터 추가 필요
  - 페이지 번호 1-based로 통일 필요

---

**문서 작성일**: 2026-01-20  
**작성자**: DWP Backend Team
