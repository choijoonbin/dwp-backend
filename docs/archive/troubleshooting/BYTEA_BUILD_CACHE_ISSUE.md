# Bytea Type Error - Build Cache Issue

## 문제 상황

```
ERROR: function lower(bytea) does not exist
```

`UserRepository.findByTenantIdAndFilters` 실행 시 `lower(bytea)` 오류 발생.

## 근본 원인

**Flyway 마이그레이션은 정상 실행되었지만, 애플리케이션이 오래된 빌드 캐시를 사용**

### 진단 과정:

1. **Flyway 히스토리 확인**:
```sql
SELECT version, description, installed_on, success 
FROM flyway_schema_history 
WHERE version = '20';
```
결과: V20 마이그레이션이 `2026-01-20 18:14:39`에 성공적으로 실행됨 (`success = t`)

2. **DB 컬럼 타입 확인**:
```sql
SELECT table_name, column_name, data_type 
FROM information_schema.columns 
WHERE table_name IN ('com_users', 'com_user_accounts') 
AND column_name IN ('display_name', 'email', 'principal', 'provider_type', 'status');
```
결과: 모든 컬럼이 `character varying` (VARCHAR)로 변경되어 있음. `bytea` 아님!

3. **엔티티 확인**:
```java
@Column(name = "display_name", nullable = false, length = 200)
private String displayName;

@Column(name = "principal", nullable = false, length = 255)
private String principal;
```
결과: 엔티티도 정상. 모두 `String` 타입.

4. **문제 식별**:
   - DB: VARCHAR ✅
   - 엔티티: String ✅
   - Flyway: 실행 완료 ✅
   - **빌드 캐시: 오래된 메타데이터 ❌**

## 해결 방법

### Step 1: Clean Build

```bash
cd /path/to/dwp-backend

# 1. Gradle clean
./gradlew :dwp-auth-server:clean

# 2. 빌드 디렉토리 및 캐시 완전 제거
rm -rf dwp-auth-server/build
rm -rf dwp-auth-server/.gradle

# 3. 다시 빌드
./gradlew :dwp-auth-server:build -x test
```

### Step 2: 서버 재시작

```bash
# 기존 프로세스 종료
pkill -f "dwp-auth-server:bootRun"

# 재시작
./gradlew :dwp-auth-server:bootRun --args='--spring.profiles.active=dev'
```

### Step 3: 검증

```bash
# 1. 서버 로그에서 bytea 오류 확인
tail -100 <서버_로그> | grep -i "lower(bytea)"
# 결과: 아무것도 출력되지 않으면 성공

# 2. Admin Users API 호출 테스트
curl -X GET "http://localhost:8001/api/admin/users?page=1&size=10" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

## 왜 이런 문제가 발생했는가?

### Hibernate Second-Level Metadata Cache

Hibernate는 엔티티 메타데이터를 캐싱합니다:
- 컬럼 타입
- 테이블 구조
- 인덱스 정보

**DB 스키마가 변경되어도, 애플리케이션이 재빌드되지 않으면 오래된 메타데이터를 계속 사용**합니다.

### 빌드 아티팩트 캐싱

Gradle은 `build/` 디렉토리에 컴파일된 클래스와 리소스를 캐싱합니다. Flyway 마이그레이션 파일이 업데이트되어도:
1. 엔티티 클래스는 재컴파일되지 않음
2. Hibernate 메타데이터도 재생성되지 않음
3. 서버 재시작만으로는 부족 (`.class` 파일이 그대로)

## 예방 방법

### 1. 스키마 변경 시 항상 Clean Build

```bash
# 스키마 변경 후
./gradlew :dwp-auth-server:clean build
```

### 2. CI/CD 파이프라인에서 강제 Clean

```yaml
# GitHub Actions 예시
- name: Build with Gradle
  run: ./gradlew clean build
```

### 3. 개발 환경에서 주기적으로 Clean

```bash
# 매주 또는 큰 변경 후
./gradlew clean
```

## 교훈

1. **Flyway 성공 != 애플리케이션 인식**
   - DB는 변경되었지만, 애플리케이션 캐시는 여전히 오래된 정보 사용
   
2. **서버 재시작 != Clean Build**
   - 서버 재시작만으로는 컴파일된 아티팩트가 갱신되지 않음
   
3. **스키마 변경 시 필수 절차**:
   ```
   Flyway 마이그레이션 → Clean Build → 서버 재시작
   ```

## 관련 문서

- [BYTEA_TYPE_FIX_GUIDE.md](./BYTEA_TYPE_FIX_GUIDE.md): 원본 bytea 타입 문제 해결 가이드
- [V20__fix_bytea_columns.sql](../dwp-auth-server/src/main/resources/db/migration/V20__fix_bytea_columns.sql): Flyway 마이그레이션 스크립트

## 결론

**근본 원인**: 빌드 캐시 문제
**해결책**: Clean build + 서버 재시작
**예방책**: 스키마 변경 시 항상 clean build 수행

---
작성일: 2026-01-20  
작성자: DWP Backend Team  
상태: ✅ 해결 완료
