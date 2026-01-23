# BE P1-2: Admin Monitoring 고도화 완료 보고

> **작성일**: 2026-01-19  
> **버전**: P1-2 Final  
> **목적**: Visitors/Events/Timeseries API 추가 및 수집 API 안정화

---

## 📋 사전 점검 결과

### 현재 스키마 상태
- `sys_page_view_events`: tenant_id BIGINT, page_key, session_id(visitorId), ip_address, user_agent, event_type/event_name/target_key/metadata_json 컬럼 존재
- `sys_page_view_daily_stats`: 일별 집계 테이블 존재 (tenant_id, stat_date, page_key 기준 UNIQUE)
- `sys_api_call_histories`: tenant_id BIGINT, Gateway에서 자동 수집 중
- `sys_event_logs`: **신규 테이블 추가** (P1-2)

### 인증 정책
- `/api/admin/**`: JWT 인증 필수 (JwtConfig에서 anyRequest().authenticated())
- `/api/monitoring/**`: 인증 제외 가능 (permitAll), 단 X-Tenant-ID 헤더 필수

### tenant_id 타입
- 모든 테이블: `BIGINT` (숫자)

### from/to 파라미터 포맷
- ISO-8601 형식 (예: `2026-01-01T00:00:00` 또는 `2026-01-01T00:00:00Z`)

---

## ✅ 구현 완료 내역

### 1. 신규 테이블: sys_event_logs
- **Flyway 마이그레이션**: V11__create_event_logs.sql
- **컬럼**: tenant_id, occurred_at, event_type, resource_key, action, label, visitor_id, user_id, path, metadata(JSONB), ip_address, user_agent
- **인덱스**: (tenant_id, occurred_at DESC), (tenant_id, visitor_id), (tenant_id, resource_key)
- **FK 제약 없음**, 모든 컬럼 COMMENT 포함

### 2. Entity & Repository
- `EventLog` 엔티티 생성 (`dwp-auth-server/entity/monitoring/EventLog.java`)
- `EventLogRepository` 생성 (페이징, 필터링, 키워드 검색 지원)

### 3. 수집 API 정리
- **MonitoringCollectService** 생성:
  - `recordPageView()`: sys_page_view_events 저장 + 일별 집계 업데이트
  - `recordEvent()`: sys_event_logs 저장
  - Validation 강화 (X-Tenant-ID 필수, 필수 필드 체크)
  - 문자열 길이 제한 (truncate)
  - Silent fail 정책 (수집 실패가 FE에 영향 없음)
- **MonitoringCollectController** 생성:
  - `POST /api/monitoring/page-view`
  - `POST /api/monitoring/event`
  - X-Tenant-ID 없으면 400 반환

### 4. Admin 조회 API 확장
- **AdminMonitoringService** 생성:
  - `getVisitors()`: 방문자 목록 조회 (페이징, 키워드 검색)
  - `getEvents()`: 이벤트 로그 목록 조회 (페이징, 필터링)
  - `getTimeseries()`: 시계열 데이터 조회 (DAY/HOUR, PV/UV/EVENT/API_TOTAL/API_ERROR)
- **AdminMonitoringController** 생성:
  - `GET /api/admin/monitoring/visitors`
  - `GET /api/admin/monitoring/events`
  - `GET /api/admin/monitoring/timeseries`
  - 기존 API 유지 (summary, page-views, api-histories)

### 5. DTO 생성
- `PageViewCollectRequest`: 페이지뷰 수집 요청
- `EventCollectRequest`: 이벤트 수집 요청
- `VisitorSummary`: 방문자 요약
- `EventLogItem`: 이벤트 로그 항목
- `TimeseriesResponse`: 시계열 데이터 응답

### 6. 보안 정책 정리
- `/api/admin/**`: JWT 인증 필수 (기존 유지)
- `/api/monitoring/**`: 인증 제외 가능, X-Tenant-ID 필수
- 향후 ADMIN role 체크 확장 가능하도록 TODO 주석 추가

### 7. 테스트 작성
- `MonitoringCollectControllerTest`: 수집 API 테스트
- `AdminMonitoringControllerTest`: 조회 API 테스트

### 8. 문서 업데이트
- `docs/ADMIN_MONITORING_API_SPEC.md`: 전체 API 명세 업데이트
- `README.md`: Monitoring API 섹션 추가

---

## 📊 주요 변경 사항

### 수집 API 변경
**Before (P0-3)**:
- `POST /api/monitoring/event` → sys_page_view_events에 저장
- X-Tenant-ID 없으면 fallback (tenantId=1L)

**After (P1-2)**:
- `POST /api/monitoring/event` → sys_event_logs에 저장
- X-Tenant-ID 없으면 400 Bad Request
- Validation 강화 (필수 필드 체크)
- Silent fail 정책 (수집 실패가 FE에 영향 없음)

### 조회 API 추가
**신규 API (P1-2)**:
- `GET /api/admin/monitoring/visitors`: 방문자 목록 조회
- `GET /api/admin/monitoring/events`: 이벤트 로그 목록 조회
- `GET /api/admin/monitoring/timeseries`: 시계열 데이터 조회

---

## 🔧 코드 구조

### 패키지 분리
```
dwp-auth-server/
├── controller/
│   ├── monitoring/
│   │   └── MonitoringCollectController.java (수집 API)
│   └── admin/monitoring/
│       └── AdminMonitoringController.java (조회 API)
├── service/
│   ├── monitoring/
│   │   ├── MonitoringCollectService.java (수집 로직)
│   │   └── AdminMonitoringService.java (조회 로직)
│   └── MonitoringService.java (기존 유지)
├── entity/monitoring/
│   └── EventLog.java
└── repository/monitoring/
    └── EventLogRepository.java
```

---

## 📝 API 응답 예시

### Visitors 조회
```json
{
  "status": "SUCCESS",
  "data": {
    "content": [
      {
        "visitorId": "visitor_123",
        "firstSeenAt": "2026-01-19T10:00:00",
        "lastSeenAt": "2026-01-19T16:00:00",
        "pageViewCount": 15,
        "eventCount": 8,
        "lastPath": "/admin/monitoring"
      }
    ],
    "totalElements": 10
  }
}
```

### Events 조회
```json
{
  "status": "SUCCESS",
  "data": {
    "content": [
      {
        "sysEventLogId": 1,
        "occurredAt": "2026-01-19T16:00:00",
        "eventType": "view",
        "resourceKey": "menu.admin.users",
        "action": "view_users",
        "label": "Admin Users 조회",
        "visitorId": "visitor_123"
      }
    ],
    "totalElements": 50
  }
}
```

### Timeseries 조회
```json
{
  "status": "SUCCESS",
  "data": {
    "interval": "DAY",
    "metric": "PV",
    "labels": ["2026-01-01", "2026-01-02", "2026-01-03"],
    "values": [100, 150, 120]
  }
}
```

---

## ✅ 완료 체크리스트

- [x] sys_event_logs 테이블 생성 (V11)
- [x] EventLog 엔티티 및 Repository 생성
- [x] 수집 API 정리 (page-view, event)
- [x] Visitors 조회 API 추가
- [x] Events 조회 API 추가
- [x] Timeseries API 추가
- [x] 보안 정책 정리 및 적용
- [x] 컨트롤러 분리 (수집/조회)
- [x] 테스트 작성 (JUnit5)
- [x] 문서 업데이트
- [x] FK 없음, COMMENT 포함, base columns 포함
- [x] tenant_id 필터 무조건 적용
- [x] ApiResponse<T> 통일

---

## 🚀 다음 단계

1. **서버 재시작 및 마이그레이션 확인**
   ```bash
   ./gradlew :dwp-auth-server:bootRun
   ```

2. **API 테스트**
   - 수집 API: X-Tenant-ID 없으면 400 확인
   - 조회 API: JWT 인증 필요 확인
   - Visitors/Events/Timeseries API 정상 동작 확인

3. **프론트엔드 통합**
   - Visitors 탭: mock 제거, 실제 API 연동
   - Events 탭: mock 제거, 실제 API 연동
   - Timeseries 차트: 실제 데이터 연동

---

## 📚 관련 문서

- [docs/ADMIN_MONITORING_API_SPEC.md](ADMIN_MONITORING_API_SPEC.md) - 전체 API 명세
- [docs/CODE_MANAGEMENT.md](CODE_MANAGEMENT.md) - 공통 코드 관리 시스템
