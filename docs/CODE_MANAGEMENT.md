# 코드 관리 시스템 (Code Management System)

## 개요

DWP Backend의 코드 관리 시스템은 공통 코드를 체계적으로 관리하고, 메뉴(리소스)별로 필요한 코드만 조회할 수 있도록 설계되었습니다.

## 핵심 설계 원칙

### 1. 메뉴별 코드 사용 범위 정의

프론트엔드는 `/api/admin/codes/all`을 사용하지 않고, **메뉴 키(resourceKey) 단위로 필요한 코드만 조회**합니다.

이를 통해:
- 불필요한 코드 전송 최소화
- 프론트엔드 코드 의존성 명확화
- 확장성 및 유지보수성 향상

### 2. 코드 그룹 ↔ 메뉴 매핑

`sys_code_usages` 테이블을 통해 메뉴(리소스)별로 사용하는 코드 그룹을 정의합니다.

```
menu.admin.users → SUBJECT_TYPE, USER_STATUS, IDP_PROVIDER_TYPE
menu.admin.roles → ROLE_CODE, SUBJECT_TYPE, PERMISSION_CODE, EFFECT_TYPE
menu.admin.resources → RESOURCE_TYPE, RESOURCE_STATUS
```

## 데이터베이스 스키마

### sys_code_groups (코드 그룹 마스터)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| sys_code_group_id | BIGSERIAL | PK |
| group_key | VARCHAR(100) | 그룹 키 (예: RESOURCE_TYPE) |
| group_name | VARCHAR(200) | 그룹명 |
| description | VARCHAR(500) | 설명 |
| is_active | BOOLEAN | 활성화 여부 |

### sys_codes (코드 마스터)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| sys_code_id | BIGSERIAL | PK |
| group_key | VARCHAR(100) | 그룹 키 (논리적 참조) |
| code | VARCHAR(100) | 코드 값 (예: MENU, USER) |
| name | VARCHAR(200) | 코드 표시명 |
| sort_order | INTEGER | 정렬 순서 |
| is_active | BOOLEAN | 활성화 여부 |

### sys_code_usages (메뉴별 코드 사용 정의)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| sys_code_usage_id | BIGSERIAL | PK |
| tenant_id | BIGINT | 테넌트 ID |
| resource_key | VARCHAR(200) | 리소스 키 (예: menu.admin.users) |
| code_group_key | VARCHAR(100) | 코드 그룹 키 |
| scope | VARCHAR(30) | 사용 범위 (MENU/PAGE/MODULE) |
| enabled | BOOLEAN | 활성화 여부 |
| sort_order | INTEGER | 정렬 순서 |

**제약조건**: `UNIQUE(tenant_id, resource_key, code_group_key)`

## API 명세

### 1. 메뉴별 코드 조회 (핵심 API)

**GET** `/api/admin/codes/usage?resourceKey={resourceKey}`

프론트엔드가 메뉴 키만 제공하면 해당 메뉴에서 사용하는 코드만 반환합니다.

**Request**
```
GET /api/admin/codes/usage?resourceKey=menu.admin.users
Headers:
  X-Tenant-ID: 1
  Authorization: Bearer {JWT}
```

**Response**
```json
{
  "success": true,
  "data": {
    "codes": {
      "SUBJECT_TYPE": [
        {
          "sysCodeId": 1,
          "code": "USER",
          "name": "사용자",
          "description": "개별 사용자",
          "sortOrder": 10,
          "enabled": true
        },
        {
          "sysCodeId": 2,
          "code": "DEPARTMENT",
          "name": "부서",
          "description": "부서 단위",
          "sortOrder": 20,
          "enabled": true
        }
      ],
      "USER_STATUS": [
        {
          "sysCodeId": 10,
          "code": "ACTIVE",
          "name": "활성",
          "sortOrder": 10,
          "enabled": true
        }
      ],
      "IDP_PROVIDER_TYPE": [
        {
          "sysCodeId": 20,
          "code": "LOCAL",
          "name": "로컬 인증",
          "sortOrder": 10,
          "enabled": true
        }
      ]
    }
  }
}
```

**매핑이 없는 경우**
```json
{
  "success": true,
  "data": {
    "codes": {}
  }
}
```

### 2. 메뉴별 사용 코드 그룹 목록 조회

**GET** `/api/admin/codes/usage/groups?resourceKey={resourceKey}`

메뉴에서 사용하는 코드 그룹 키 목록만 반환합니다.

**Response**
```json
{
  "success": true,
  "data": [
    "SUBJECT_TYPE",
    "USER_STATUS",
    "IDP_PROVIDER_TYPE"
  ]
}
```

### 3. 코드 사용 정의 CRUD

#### 목록 조회

**GET** `/api/admin/code-usages?page=1&size=20&resourceKey={resourceKey}&keyword={keyword}`

**Response**
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "sysCodeUsageId": 1,
        "tenantId": 1,
        "resourceKey": "menu.admin.users",
        "codeGroupKey": "SUBJECT_TYPE",
        "scope": "MENU",
        "enabled": true,
        "sortOrder": 10,
        "remark": "사용자 관리 화면에서 사용"
      }
    ],
    "page": 1,
    "size": 20,
    "totalItems": 1,
    "totalPages": 1
  }
}
```

#### 생성

**POST** `/api/admin/code-usages`

**Request**
```json
{
  "resourceKey": "menu.admin.users",
  "codeGroupKey": "SUBJECT_TYPE",
  "scope": "MENU",
  "enabled": true,
  "sortOrder": 10,
  "remark": "사용자 관리 화면에서 사용"
}
```

#### 수정

**PATCH** `/api/admin/code-usages/{sysCodeUsageId}`

**Request**
```json
{
  "enabled": false,
  "remark": "비활성화"
}
```

#### 삭제

**DELETE** `/api/admin/code-usages/{sysCodeUsageId}`

## 운영 권장 정책

### 1. `/api/admin/codes/all` 사용 금지

프론트엔드는 반드시 `/api/admin/codes/usage?resourceKey={resourceKey}`를 사용해야 합니다.

**이유:**
- 불필요한 데이터 전송 최소화
- 메뉴별 코드 의존성 명확화
- 확장성 확보

### 2. 코드 사용 정의 관리

새로운 메뉴가 추가되면 반드시 `sys_code_usages`에 매핑을 추가합니다.

**예시:**
```sql
INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order)
VALUES (1, 'menu.admin.new', 'RESOURCE_TYPE', 'MENU', true, 10);
```

### 3. 캐싱 전략

`CodeUsageService`는 리소스 키 기준으로 인메모리 캐싱을 제공합니다.

- 캐시 키: `{tenantId}:{resourceKey}`
- 코드 사용 정의 변경 시 자동 캐시 무효화
- 수동 캐시 초기화: `CodeUsageService.clearCache(tenantId, resourceKey)`

## Curl 예시

### 메뉴별 코드 조회

```bash
curl -X GET "http://localhost:8080/api/admin/codes/usage?resourceKey=menu.admin.users" \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1"
```

### 코드 사용 정의 생성

```bash
curl -X POST "http://localhost:8080/api/admin/code-usages" \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1" \
  -H "Content-Type: application/json" \
  -d '{
    "resourceKey": "menu.admin.users",
    "codeGroupKey": "SUBJECT_TYPE",
    "scope": "MENU",
    "enabled": true,
    "sortOrder": 10
  }'
```

## 테스트

### 필수 테스트 케이스

1. **메뉴별 코드 조회**
   - 매핑이 있는 경우: 코드 그룹별 코드 목록 반환 확인
   - 매핑이 없는 경우: 빈 맵 반환 확인

2. **코드 사용 정의 CRUD**
   - 생성: 중복 체크, 감사 로그 기록 확인
   - 수정: 캐시 무효화 확인
   - 삭제: 캐시 무효화 확인

3. **멀티테넌시 격리**
   - 다른 테넌트의 데이터는 조회되지 않음 확인

## 관련 파일

- Entity: `CodeUsage.java`
- Repository: `CodeUsageRepository.java`
- Service: `CodeUsageService.java`
- Controller: `CodeUsageController.java`, `CodeController.java`
- Migration: `V12__create_sys_code_usages.sql`, `V13__seed_sys_code_usages.sql`
