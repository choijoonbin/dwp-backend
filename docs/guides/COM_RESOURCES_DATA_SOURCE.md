# com_resources 테이블 데이터 소스 가이드

**작성일**: 2026-01-20  
**목적**: `com_resources` 테이블의 데이터가 어디서 오는지 명확히 설명

---

## 📊 데이터 소스 요약

`com_resources` 테이블의 데이터는 **두 가지 소스**에서 옵니다:

1. **초기 데이터 (Seed 데이터)**: 마이그레이션 파일에 하드코딩된 데이터
2. **운영 데이터**: 프론트엔드(Admin UI)에서 CRUD API를 통해 관리하는 데이터

---

## 1. 초기 데이터 (Seed 데이터)

### 마이그레이션 파일

**V2__insert_seed_data.sql** (기본 메뉴):
- `menu.dashboard` - Dashboard
- `menu.mail` - Mail
  - `menu.mail.inbox` - Inbox
  - `menu.mail.sent` - Sent
- `menu.ai-workspace` - AI Workspace
- `btn.mail.send` - Send Button (UI Component)
- `btn.mail.delete` - Delete Button (UI Component)

**V5__add_admin_menu_resources.sql** (Admin 메뉴):
- `menu.admin` - Admin
  - `menu.admin.monitoring` - 통합 모니터링
  - `menu.admin.users` - 사용자 관리
  - `menu.admin.roles` - 역할 관리
  - `menu.admin.resources` - 리소스 관리
  - `menu.admin.audit` - 감사 로그

### Seed 데이터 목적

- **개발 환경 초기화**: 로컬 개발 시 기본 메뉴 구조 제공
- **시스템 기본 리소스**: 시스템 운영에 필수적인 리소스 정의
- **테스트 데이터**: 개발 및 테스트를 위한 샘플 데이터

---

## 2. 운영 데이터 (프론트엔드 관리)

### CRUD API

프론트엔드(Admin UI)에서 리소스를 관리할 수 있는 API가 제공됩니다:

#### 리소스 조회
- **GET** `/api/admin/resources` - 리소스 목록 조회 (페이징)
- **GET** `/api/admin/resources/tree` - 리소스 트리 조회

#### 리소스 생성
- **POST** `/api/admin/resources` - 리소스 생성

**Request Body 예시**:
```json
{
  "resourceType": "MENU",
  "resourceKey": "menu.custom.newmenu",
  "resourceName": "새 메뉴",
  "parentResourceKey": "menu.admin",
  "path": "/admin/newmenu",
  "sortOrder": 100,
  "metadata": {
    "icon": "settings"
  }
}
```

#### 리소스 수정
- **PUT** `/api/admin/resources/{comResourceId}` - 리소스 수정

#### 리소스 삭제
- **DELETE** `/api/admin/resources/{comResourceId}` - 리소스 삭제

---

## 3. 데이터 관리 방식

### 초기 데이터 (Seed)

```
마이그레이션 파일 (V2, V5)
  ↓
Flyway 실행 시 자동 삽입
  ↓
com_resources 테이블에 저장
```

**특징**:
- ✅ DB 마이그레이션 시 자동 생성
- ✅ 개발 환경 초기화에 필수
- ✅ 수동으로 삭제/수정 가능 (하지만 권장하지 않음)

### 운영 데이터 (프론트엔드)

```
프론트엔드 (Admin UI)
  ↓
POST /api/admin/resources
  ↓
ResourceManagementService.createResource()
  ↓
com_resources 테이블에 저장
```

**특징**:
- ✅ 프론트엔드에서 동적으로 생성/수정/삭제 가능
- ✅ AuditLog에 기록됨
- ✅ 권한 체크 (ADMIN 권한 필요)

---

## 4. 현재 데이터 상태 확인

### Seed 데이터 확인

```sql
-- Seed 데이터로 생성된 리소스 확인
SELECT resource_id, type, key, name, parent_resource_id
FROM com_resources
WHERE tenant_id = 1
ORDER BY resource_id;
```

**예상 결과**:
- resource_id 1-7: V2 Seed 데이터 (Dashboard, Mail, AI Workspace 등)
- resource_id 8-13: V5 Seed 데이터 (Admin 메뉴들)

### 운영 데이터 확인

```sql
-- 프론트엔드에서 생성한 리소스 확인 (resource_id가 13보다 큰 경우)
SELECT resource_id, type, key, name, created_at, created_by
FROM com_resources
WHERE tenant_id = 1
  AND resource_id > 13
ORDER BY created_at DESC;
```

---

## 5. 데이터 관리 권장사항

### Seed 데이터

- **수정 금지**: Seed 데이터는 시스템 기본 리소스이므로 수정하지 않는 것을 권장
- **삭제 금지**: Seed 데이터를 삭제하면 시스템 기능에 문제가 발생할 수 있음
- **확장 가능**: Seed 데이터를 기반으로 새로운 리소스를 추가할 수 있음

### 운영 데이터

- **프론트엔드에서 관리**: Admin UI의 "리소스 관리" 메뉴에서 관리
- **AuditLog 기록**: 모든 생성/수정/삭제 작업이 AuditLog에 기록됨
- **권한 체크**: ADMIN 권한이 필요함

---

## 6. 리소스 관리 UI 사용법

### 리소스 생성 예시

1. Admin UI → 리소스 관리 메뉴 접근
2. "새 리소스" 버튼 클릭
3. 리소스 정보 입력:
   - 타입: MENU, UI_COMPONENT, PAGE, API 중 선택
   - 키: `menu.custom.example` (고유해야 함)
   - 이름: "예제 메뉴"
   - 부모 리소스: 선택 (트리 구조)
   - 경로: `/admin/example`
   - 메타데이터: 아이콘, 정렬 순서 등
4. 저장

### 리소스 수정/삭제

- 리소스 목록에서 수정/삭제 버튼 클릭
- 수정: 리소스 정보 변경 후 저장
- 삭제: 확인 후 삭제 (Soft Delete 권장)

---

## 7. 데이터 흐름도

```
[초기 데이터]
마이그레이션 파일 (V2, V5)
  ↓
Flyway 실행
  ↓
com_resources 테이블 (resource_id: 1-13)

[운영 데이터]
프론트엔드 (Admin UI)
  ↓
POST /api/admin/resources
  ↓
ResourceManagementService
  ↓
com_resources 테이블 (resource_id: 14+)
  ↓
AuditLog 기록
```

---

## 8. 요약

| 데이터 유형 | 소스 | 관리 방법 | 수정 가능 여부 |
|------------|------|----------|---------------|
| **초기 데이터** | 마이그레이션 파일 (Seed) | Flyway 자동 삽입 | ⚠️ 수정 가능하나 권장하지 않음 |
| **운영 데이터** | 프론트엔드 (Admin UI) | CRUD API | ✅ 프론트엔드에서 자유롭게 관리 |

---

## 9. 결론

**`com_resources` 테이블의 데이터는**:
1. **초기 데이터**: 마이그레이션 파일에 하드코딩된 Seed 데이터 (임의로 넣은 데이터)
2. **운영 데이터**: 프론트엔드에서 CRUD API를 통해 관리하는 데이터

**프론트엔드에서**:
- ✅ 리소스를 생성/수정/삭제할 수 있습니다
- ✅ Admin UI의 "리소스 관리" 메뉴에서 관리합니다
- ✅ 모든 작업이 AuditLog에 기록됩니다

---

**문서 작성일**: 2026-01-20  
**작성자**: DWP Backend Team
