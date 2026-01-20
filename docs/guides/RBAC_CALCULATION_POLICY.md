# RBAC 권한 계산 정책

## 개요

DWP Backend의 RBAC(Role-Based Access Control) 권한 계산 정책을 정의합니다.

## 권한 계산 컴포넌트

### PermissionCalculator
- **위치**: `com.dwp.services.auth.service.rbac.PermissionCalculator`
- **책임**: 권한 계산 핵심 로직 (단일 컴포넌트로 통일)
- **테스트 대상**: 권한 계산 로직의 정확성 검증

## 권한 계산 순서

### 1. 사용자 역할 조회
- **직접 역할**: `com_role_members`에서 `subject_type=USER` 조회
- **부서 역할**: 사용자의 `primary_department_id`를 통해 `subject_type=DEPARTMENT` 조회
- **병합**: 두 역할 목록을 합산하여 최종 역할 ID 목록 생성

```java
List<Long> roleIds = getAllRoleIds(tenantId, userId);
// USER + DEPARTMENT role 합산
```

### 2. 역할-권한 매핑 조회
- `com_role_permissions`에서 역할 ID 목록으로 권한 매핑 조회
- `resource_id`, `permission_id`, `effect` (ALLOW/DENY) 포함

### 3. DENY 우선 정책 적용
- **DENY 우선**: DENY가 하나라도 있으면 거부
- **ALLOW 확인**: DENY가 없을 때만 ALLOW 확인
- **기본값**: 아무것도 없으면 거부

```java
// DENY 우선 정책
boolean hasDeny = rolePermissions.stream()
    .anyMatch(rp -> rp.getResourceId().equals(resource.getResourceId()) &&
                   rp.getPermissionId().equals(permission.getPermissionId()) &&
                   "DENY".equals(rp.getEffect()));

if (hasDeny) {
    return false;  // DENY 우선
}

// ALLOW 확인
boolean hasAllow = rolePermissions.stream()
    .anyMatch(rp -> rp.getResourceId().equals(resource.getResourceId()) &&
                   rp.getPermissionId().equals(permission.getPermissionId()) &&
                   "ALLOW".equals(rp.getEffect()));

return hasAllow;
```

## 정렬 안정성 보장

### PermissionTuple 정렬 규칙
- **1차 정렬**: `resourceKey` (알파벳 순)
- **2차 정렬**: `permissionCode` (알파벳 순)
- **3차 정렬**: `effect` (ALLOW/DENY 순)

```java
Set<PermissionTuple> permissionSet = new TreeSet<>(
    Comparator.comparing(PermissionTuple::getResourceKey)
        .thenComparing(PermissionTuple::getPermissionCode)
        .thenComparing(PermissionTuple::getEffect)
);
```

### 정렬 안정성 보장 이유
- API 응답의 일관성 유지
- 캐시 키 생성 시 안정성 보장
- 테스트 결과의 재현성 보장

## 확장 포인트

### 1. ADMIN 역할 처리
- `AdminGuardService`에서 ADMIN 역할 확인
- ADMIN이면 모든 권한 허용 (PermissionCalculator 호출 전 처리)

```java
if (hasAdminRole(tenantId, userId)) {
    return true;  // ADMIN은 모든 권한 허용
}
```

### 2. 부서 역할 확장
- 현재: `primary_department_id`만 고려
- 향후: 다중 부서 역할 지원 가능 (확장 포인트)

### 3. 동적 권한 계산
- 현재: DB 기반 정적 권한 계산
- 향후: 시간 기반/조건 기반 동적 권한 계산 가능 (확장 포인트)

## 테스트 전략

### 필수 테스트 케이스
1. **사용자 역할 + 부서 역할 합산**
   - 사용자 직접 역할과 부서 역할이 모두 반영되는지 검증

2. **DENY 우선 정책**
   - ALLOW와 DENY가 동시에 있을 때 DENY가 우선되는지 검증

3. **tenant 격리**
   - 다른 tenantId로 접근 시 권한이 없는지 검증

4. **effect 포함 확인**
   - ALLOW/DENY effect가 정확히 반영되는지 검증

5. **정렬 안정성**
   - 동일한 권한 목록이 항상 동일한 순서로 반환되는지 검증

## 성능 고려사항

### 캐싱 전략
- `AdminGuardService`에서 권한 목록 캐싱 (5분 TTL)
- `PermissionCalculator`는 순수 계산 로직 (캐싱 없음)

### 쿼리 최적화
- 역할 ID 목록 조회 시 IN 절 사용
- 리소스/권한 정보 조회 시 배치 조회 (N+1 방지)

## 보안 고려사항

### tenant_id 격리
- 모든 권한 계산에 `tenantId` 필터 필수
- 다른 tenantId로 접근 시 권한 없음 처리

### CodeResolver 검증
- `PERMISSION_CODE`, `EFFECT_TYPE` 등은 CodeResolver로 검증
- 하드코딩 금지
