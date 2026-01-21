# bytea 타입 오류 방지 가이드

## ⚠️ 중요: 반드시 읽고 준수하세요

이 가이드는 PostgreSQL에서 `lower(bytea)` 오류를 방지하기 위한 **필수 체크리스트**입니다.

## 문제 상황

```
ERROR: function lower(bytea) does not exist
Hint: No function matches the given name and argument types. You might need to add explicit type casts.
```

이 오류는 Hibernate가 String 필드를 `bytea` 타입으로 잘못 인식할 때 발생합니다.

## 발생 원인

1. **엔티티 필드에 `columnDefinition` 누락**: Hibernate가 DB 스키마를 확인하지 않고 타입을 추론
2. **JPQL에서 `LOWER()` 함수 사용**: String 필드에 `LOWER()`를 사용할 때 Hibernate가 `bytea`로 인식
3. **Hibernate 메타데이터 캐시**: `columnDefinition`을 추가해도 캐시 때문에 즉시 반영되지 않을 수 있음

## 해결 방법

### 방법 1: 엔티티에 `columnDefinition` 추가 (권장)

**모든 String 필드에 `columnDefinition`을 명시적으로 추가하세요.**

```java
// ❌ 잘못된 예
@Column(name = "resource_key", nullable = false, length = 200)
private String resourceKey;

// ✅ 올바른 예
@Column(name = "resource_key", nullable = false, length = 200, columnDefinition = "VARCHAR(200)")
private String resourceKey;
```

### 방법 2: Native Query 사용 (필요시)

`columnDefinition`만으로 해결되지 않는 경우, Repository에서 Native Query를 사용하세요.

```java
@Query(value = "SELECT cu.sys_code_usage_id, cu.tenant_id, cu.resource_key, ... " +
       "FROM sys_code_usages cu " +
       "WHERE cu.tenant_id = :tenantId " +
       "AND (:keyword IS NULL OR :keyword = '' OR " +
       "     LOWER(CAST(cu.resource_key AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
       "...",
       nativeQuery = true,
       countQuery = "...")
Page<CodeUsage> findByTenantIdAndFilters(...);
```

**주의사항:**
- Native Query 사용 시 모든 컬럼을 명시적으로 나열해야 함
- `countQuery`도 함께 제공해야 함
- Native Query 사용은 예외 승인 필요 (프로젝트 룰)

## 필수 체크리스트

### ✅ 새 엔티티 생성 시

- [ ] 모든 `String` 타입 필드에 `columnDefinition = "VARCHAR(길이)"` 추가
- [ ] `LOWER()`, `LIKE`, `CONCAT()` 등 문자열 함수를 사용하는 JPQL 쿼리가 있다면 테스트 필수
- [ ] 키워드 검색 기능이 포함된 Repository 메서드는 Native Query 고려

### ✅ Repository 쿼리 작성 시

- [ ] `LOWER(field)` 사용 시 해당 필드에 `columnDefinition`이 있는지 확인
- [ ] `LIKE` 연산자 사용 시 `columnDefinition` 확인
- [ ] 문제 발생 시 Native Query로 전환 고려

### ✅ 기존 코드 수정 시

- [ ] `LOWER()` 함수를 사용하는 모든 JPQL 쿼리 확인
- [ ] 해당 엔티티 필드에 `columnDefinition` 추가
- [ ] 애플리케이션 재시작 후 테스트

## 영향받는 엔티티 목록

다음 엔티티들은 이미 수정되었습니다:

- ✅ `CodeUsage`: `resourceKey`, `codeGroupKey`, `scope`
- ✅ `AuditLog`: `action`, `resourceType`
- ✅ `EventLog`: `eventType`, `resourceKey`, `resourceKind`, `action`, `label`, `path`, `visitorId`
- ✅ `ApiCallHistory`: `agentId`, `source`, `method`, `path`
- ✅ `Resource`: `type`, `key`, `name`, `resourceCategory`, `resourceKind`, `eventKey`, `uiScope`

## 추가 확인이 필요한 엔티티

다음 엔티티들도 `LOWER()` 함수를 사용하는 쿼리가 있을 수 있습니다:

- `User`: `displayName`, `email`
- `UserAccount`: `principal`, `providerType`, `status`
- `Role`: `name`, `code`, `status`
- `Menu`: `menuKey`, `menuName`, `menuPath`
- `Code`: `code`, `name`
- `CodeGroup`: `groupKey`, `groupName`
- `Department`: `name`, `code`

## 테스트 방법

1. **컴파일 확인**
   ```bash
   ./gradlew :dwp-auth-server:compileJava
   ```

2. **애플리케이션 재시작**
   - Hibernate 메타데이터 캐시 갱신을 위해 필수

3. **API 테스트**
   - 키워드 검색 기능이 포함된 모든 API 엔드포인트 테스트
   - 예: `/api/admin/code-usages?keyword=test`

## 예외 승인 절차

Native Query 사용이 필요한 경우:

1. `docs/audit/NATIVE_QUERY_EXCEPTION_APPROVAL.md` 참고
2. 예외 승인 문서 작성
3. Tech Lead 리뷰 요청

## 참고 문서

- `docs/troubleshooting/BYTEA_TYPE_FIX_GUIDE.md`
- `docs/troubleshooting/BYTEA_CODEUSAGE_FIX.md`
- `docs/audit/NATIVE_QUERY_EXCEPTION_APPROVAL.md`

## 요약

**핵심 원칙:**
1. **모든 String 필드에 `columnDefinition` 추가**
2. **`LOWER()` 함수 사용 시 반드시 테스트**
3. **문제 발생 시 Native Query 고려**

이 가이드를 준수하면 `lower(bytea)` 오류를 방지할 수 있습니다.
