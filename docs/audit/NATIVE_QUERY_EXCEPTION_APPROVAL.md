# Native Query 사용 예외 승인 문서

## 작성일
2026-01-21

## 승인 요청
- **요청자**: 개발팀
- **승인자**: Tech Lead (검토 필요)
- **승인 상태**: 대기 중

---

## 1. 사용 위치

### RoleRepository.java
- **메서드**: `findByTenantIdAndKeyword`
- **위치**: `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/RoleRepository.java` (43-58줄)
- **사용 이유**: bytea 타입을 VARCHAR로 명시적 변환

### UserRepository.java
- **메서드**: `findByTenantIdAndFilters`
- **위치**: `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/UserRepository.java` (42-72줄)
- **사용 이유**: bytea 타입을 VARCHAR로 명시적 변환 및 LEFT JOIN 처리

---

## 2. 사용 사유

### 기술적 배경
- V20 마이그레이션에서 `com_roles.name`, `com_roles.code`, `com_user_accounts.principal` 컬럼이 `bytea`에서 `VARCHAR`로 변경됨
- 하지만 Hibernate가 여전히 해당 컬럼을 `bytea`로 인식하여 JPQL에서 `LOWER()` 함수 사용 시 오류 발생
- 오류: `ERROR: function lower(bytea) does not exist`

### 해결 방법
1. **Native Query 사용**: `CAST(column AS VARCHAR)` 명시적 변환
2. **대안 검토**: QueryDSL 사용 시에도 동일한 CAST 필요

### SQL 예시
```sql
-- RoleRepository
SELECT r.* FROM com_roles r 
WHERE r.tenant_id = :tenantId 
AND (:keyword IS NULL OR 
     LOWER(CAST(r.name AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR 
     LOWER(CAST(r.code AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))) 
ORDER BY CAST(r.name AS VARCHAR) ASC

-- UserRepository
SELECT DISTINCT u.user_id, u.tenant_id, u.display_name, u.email, ...
FROM com_users u 
LEFT JOIN com_user_accounts ua ON ua.user_id = u.user_id AND ua.tenant_id = u.tenant_id 
WHERE u.tenant_id = :tenantId 
AND (:keyword IS NULL OR 
     LOWER(u.display_name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR 
     LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR 
     (ua.principal IS NOT NULL AND LOWER(CAST(ua.principal AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))))
```

---

## 3. 대안 검토

### 대안 1: QueryDSL 사용
- **장점**: 타입 안정성, 컴파일 타임 검증
- **단점**: CAST를 QueryDSL에서도 동일하게 처리해야 함, 복잡도 증가
- **결론**: Native Query와 동일한 CAST 필요, 추가 이점 없음

### 대안 2: Hibernate 타입 매핑 수정
- **장점**: 근본적 해결
- **단점**: Entity 수정 필요, 기존 코드 영향 범위 큼, 리스크 높음
- **결론**: 현재 단계에서는 리스크가 큼

### 대안 3: 별도 조회 메서드 분리
- **장점**: Native Query 범위 최소화
- **단점**: 코드 중복, 유지보수 복잡도 증가
- **결론**: 현재 구조가 더 명확함

---

## 4. 성능 검증

### Explain/Analyze 결과
```sql
-- RoleRepository 쿼리 성능
EXPLAIN ANALYZE
SELECT r.* FROM com_roles r 
WHERE r.tenant_id = 1 
AND LOWER(CAST(r.name AS VARCHAR)) LIKE LOWER('%admin%')
ORDER BY CAST(r.name AS VARCHAR) ASC;

-- 결과: 인덱스 사용 정상, 성능 이슈 없음
```

### 인덱스 확인
- `com_roles.tenant_id` 인덱스 존재 ✅
- `com_users.tenant_id` 인덱스 존재 ✅
- `com_user_accounts.user_id`, `tenant_id` 복합 인덱스 존재 ✅

---

## 5. 보안 검증

### SQL Injection 방지
- ✅ Spring Data JPA의 `@Param` 사용으로 파라미터 바인딩
- ✅ 사용자 입력값 직접 문자열 연결 없음
- ✅ PreparedStatement 사용 (Spring Data JPA 기본 동작)

### 테넌트 격리
- ✅ 모든 쿼리에 `tenant_id` 필터 포함
- ✅ 멀티테넌시 격리 보장

---

## 6. 유지보수 계획

### 문서화
- ✅ 본 문서 작성 완료
- ✅ 코드 주석에 Native Query 사용 사유 명시

### 모니터링
- 성능 모니터링: 쿼리 실행 시간 추적
- 오류 모니터링: SQL 오류 발생 시 알림

### 향후 개선
- Hibernate 버전 업그레이드 시 타입 매핑 재검토
- QueryDSL 도입 시 동일한 CAST 패턴 적용

---

## 7. 승인 요청

### 예외 승인 요청 사항
1. `RoleRepository.findByTenantIdAndKeyword` 메서드의 Native Query 사용 승인
2. `UserRepository.findByTenantIdAndFilters` 메서드의 Native Query 사용 승인

### 승인 조건
- ✅ 기술적 사유 명확
- ✅ 대안 검토 완료
- ✅ 성능 검증 완료
- ✅ 보안 검증 완료
- ✅ 문서화 완료

### 승인자 검토 사항
- [ ] 기술적 사유 검토
- [ ] 대안 검토 확인
- [ ] 성능 검증 확인
- [ ] 보안 검증 확인
- [ ] 문서화 확인

---

## 8. 승인 기록

| 날짜 | 승인자 | 상태 | 비고 |
|------|--------|------|------|
| 2026-01-21 | - | 대기 중 | 초기 문서 작성 |

---

## 참고
- 커서룰즈 규칙: `🧾 Persistence Rule: JPA + QueryDSL Only (Native Query Prohibited)`
- 예외 승인 절차: ADR 또는 docs/에 사유/SQL/대안 검토/성능 근거 문서화 후 tech lead 리뷰 승인
