# CodeUsage 엔티티 bytea 타입 오류 수정

## 문제 상황

`/api/admin/code-usages` API 호출 시 다음과 같은 오류 발생:

```
ERROR: function lower(bytea) does not exist
Hint: No function matches the given name and argument types. You might need to add explicit type casts.
```

## 원인

Hibernate가 `CodeUsage` 엔티티의 `resourceKey`와 `codeGroupKey` 필드를 `bytea` 타입으로 잘못 인식하여, `LOWER()` 함수를 사용할 수 없었습니다.

이는 Hibernate의 메타데이터 캐싱 문제로, PostgreSQL에서 컬럼 타입을 정확히 인식하지 못할 때 발생합니다.

## 해결 방법

`CodeUsage` 엔티티의 `resourceKey`, `codeGroupKey`, `scope` 필드에 `columnDefinition`을 명시적으로 추가:

```java
@Column(name = "resource_key", nullable = false, length = 200, columnDefinition = "VARCHAR(200)")
private String resourceKey;

@Column(name = "code_group_key", nullable = false, length = 100, columnDefinition = "VARCHAR(100)")
private String codeGroupKey;

@Column(name = "scope", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
private String scope;
```

## 적용된 변경사항

- **파일**: `dwp-auth-server/src/main/java/com/dwp/services/auth/entity/CodeUsage.java`
- **수정일**: 2026-01-22
- **관련 이슈**: `lower(bytea)` 오류

## 참고

이 문제는 이전에 `AuditLog` 엔티티에서도 동일하게 발생했으며, 같은 방식으로 해결했습니다.

- `AuditLog.action`: `columnDefinition = "VARCHAR(100)"`
- `AuditLog.resourceType`: `columnDefinition = "VARCHAR(100)"`

## 다음 단계

1. 애플리케이션 재시작 (Hibernate 메타데이터 캐시 갱신)
2. `/api/admin/code-usages` API 테스트
3. 키워드 검색 기능 정상 동작 확인
