# bytea 타입 오류 종합 수정 요약

## 수정 일자
2026-01-22

## 문제 상황

`/api/admin/code-usages` API 호출 시 `lower(bytea)` 오류가 계속 발생했습니다.

```
ERROR: function lower(bytea) does not exist
```

## 근본 원인

1. **Hibernate 메타데이터 캐시 문제**: `columnDefinition`을 추가해도 Hibernate가 여전히 String 필드를 `bytea`로 인식
2. **JPQL의 타입 추론 한계**: JPQL에서 `LOWER()` 함수 사용 시 Hibernate가 DB 스키마를 확인하지 않고 엔티티 정의만 보고 타입 추론

## 적용된 수정 사항

### 1. CodeUsageRepository - Native Query 전환

**파일**: `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/CodeUsageRepository.java`

- JPQL에서 Native Query로 전환
- `CAST(column AS VARCHAR)` 명시적 변환 사용
- `countQuery` 추가

```java
@Query(value = "SELECT cu.sys_code_usage_id, cu.tenant_id, cu.resource_key, ... " +
       "FROM sys_code_usages cu " +
       "WHERE cu.tenant_id = :tenantId " +
       "AND (:keyword IS NULL OR :keyword = '' OR " +
       "     LOWER(CAST(cu.resource_key AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
       "     LOWER(CAST(cu.code_group_key AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
       "...",
       nativeQuery = true,
       countQuery = "...")
Page<CodeUsage> findByTenantIdAndFilters(...);
```

### 2. 엔티티 String 필드에 columnDefinition 추가

#### CodeUsage 엔티티
- `resourceKey`: `columnDefinition = "VARCHAR(200)"`
- `codeGroupKey`: `columnDefinition = "VARCHAR(100)"`
- `scope`: `columnDefinition = "VARCHAR(30)"`

#### EventLog 엔티티
- `eventType`: `columnDefinition = "VARCHAR(50)"`
- `resourceKey`: `columnDefinition = "VARCHAR(255)"`
- `resourceKind`: `columnDefinition = "VARCHAR(50)"`
- `action`: `columnDefinition = "VARCHAR(100)"`
- `label`: `columnDefinition = "VARCHAR(200)"`
- `visitorId`: `columnDefinition = "VARCHAR(255)"`
- `path`: `columnDefinition = "VARCHAR(500)"`

#### ApiCallHistory 엔티티
- `agentId`: `columnDefinition = "VARCHAR(100)"`
- `source`: `columnDefinition = "VARCHAR(20)"`
- `method`: `columnDefinition = "VARCHAR(10)"`
- `path`: `columnDefinition = "VARCHAR(500)"`

#### Resource 엔티티
- `type`: `columnDefinition = "VARCHAR(20)"`
- `key`: `columnDefinition = "VARCHAR(255)"`
- `name`: `columnDefinition = "VARCHAR(200)"`
- `resourceCategory`: `columnDefinition = "VARCHAR(50)"`
- `resourceKind`: `columnDefinition = "VARCHAR(50)"`
- `eventKey`: `columnDefinition = "VARCHAR(120)"`
- `uiScope`: `columnDefinition = "VARCHAR(30)"`

## 추가 확인이 필요한 엔티티

다음 엔티티들도 `LOWER()` 함수를 사용하는 쿼리가 있을 수 있으므로 주의가 필요합니다:

- `User`: `displayName`, `email`
- `UserAccount`: `principal`, `providerType`, `status`
- `Role`: `name`, `code`, `status`
- `Menu`: `menuKey`, `menuName`, `menuPath`
- `Code`: `code`, `name`
- `CodeGroup`: `groupKey`, `groupName`
- `Department`: `name`, `code`

## 개발 가이드

**필수 체크리스트** (`docs/guides/BYTEA_ERROR_PREVENTION_GUIDE.md` 참고):

1. ✅ 모든 String 필드에 `columnDefinition = "VARCHAR(길이)"` 추가
2. ✅ `LOWER()` 함수 사용 시 해당 필드에 `columnDefinition` 확인
3. ✅ 문제 발생 시 Native Query 고려

## 다음 단계

1. **애플리케이션 재시작** (Hibernate 메타데이터 캐시 갱신)
2. **API 테스트**: `/api/admin/code-usages?keyword=test`
3. **다른 API도 테스트**: 키워드 검색 기능이 포함된 모든 엔드포인트

## 참고 문서

- `docs/guides/BYTEA_ERROR_PREVENTION_GUIDE.md` - 개발 가이드
- `docs/troubleshooting/BYTEA_CODEUSAGE_FIX.md` - CodeUsage 수정 상세
- `docs/audit/NATIVE_QUERY_EXCEPTION_APPROVAL.md` - Native Query 예외 승인
