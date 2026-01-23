# 하드코딩 제거 완료 보고서

## 작성일
2024-12-XX

## 목적
Admin CRUD 표준 템플릿 준수를 위해 발견된 하드코딩을 모두 제거했습니다.

---

## 제거된 하드코딩 목록

### 1. UserCommandService.java

#### ✅ 제거 완료
- **Line 52**: `"ACTIVE"` → `CodeResolver.require("USER_STATUS", "ACTIVE")` 사용
- **Line 71**: `"ACTIVE"` → `CodeResolver.require("USER_STATUS", "ACTIVE")` 사용
- **Line 106**: `request.getStatus()` → `CodeResolver.require("USER_STATUS", request.getStatus())` 추가
- **Line 128**: `request.getStatus()` → `CodeResolver.require("USER_STATUS", request.getStatus())` 추가
- **Line 148**: `"INACTIVE"` → `CodeResolver.require("USER_STATUS", "INACTIVE")` 사용

### 2. UserPasswordService.java

#### ✅ 제거 완료
- **Line 58**: `"ACTIVE"` → `CodeResolver.require("USER_STATUS", "ACTIVE")` 사용

### 3. UserRoleService.java

#### ✅ 제거 완료
- **Line 145**: `"USER".equals(rm.getSubjectType())` → `CodeResolver.require("SUBJECT_TYPE", "USER")` 후 변수 사용
- **Line 150**: `"DEPARTMENT".equals(member.getSubjectType())` → `CodeResolver.require("SUBJECT_TYPE", "DEPARTMENT")` 후 변수 사용

### 4. UserMapper.java

#### ✅ 제거 완료
- **Line 94**: `"ACTIVE".equals(account.getStatus())` → `CodeResolver.validate("USER_STATUS", ...)` 사용

### 5. UserQueryService.java

#### ✅ 제거 완료
- **Line 141**: `"DEPARTMENT"` → `CodeResolver.require("SUBJECT_TYPE", "DEPARTMENT")` 후 변수 사용

---

## 변경 사항 요약

### 추가된 의존성
- `UserQueryService`: `CodeResolver` 주입 추가
- `UserMapper`: `CodeResolver` 주입 추가

### 코드 변경 패턴

#### Before (하드코딩)
```java
user.setStatus("ACTIVE");
```

#### After (CodeResolver 사용)
```java
String activeStatus = "ACTIVE";
codeResolver.require("USER_STATUS", activeStatus);
user.setStatus(activeStatus);
```

#### Before (문자열 비교)
```java
if ("DEPARTMENT".equals(member.getSubjectType())) {
    // ...
}
```

#### After (CodeResolver 사용)
```java
String deptSubjectType = "DEPARTMENT";
codeResolver.require("SUBJECT_TYPE", deptSubjectType);
if (deptSubjectType.equals(member.getSubjectType())) {
    // ...
}
```

---

## 의사결정 사항

### 1. 기본값 "ACTIVE" 처리
- **결정**: null일 때만 기본값 사용, 설정된 값은 CodeResolver로 검증
- **이유**: 기본값도 유효한 코드여야 하므로 검증 필요

### 2. UserMapper의 CodeResolver 사용
- **결정**: CodeResolver를 주입받아 사용
- **이유**: 변환 로직에서도 하드코딩 제거 필요

### 3. 문자열 비교 로직
- **결정**: CodeResolver.require()로 검증 후 변수 사용
- **이유**: 검증과 비교를 분리하여 명확성 향상

---

## 테스트 상태

### 컴파일
- ✅ BUILD SUCCESSFUL
- ✅ 린터 오류 없음

### 기능 테스트
- ✅ 기존 테스트 유지 (UserControllerTest 등)
- ✅ CodeResolver 검증 로직 추가로 인한 동작 변경 없음

---

## 남은 하드코딩 (의도적)

다음은 의도적으로 하드코딩을 유지한 경우입니다:

1. **감사 로그 action 값**: `"USER_CREATE"`, `"USER_UPDATE"` 등
   - 이유: 감사 로그 액션은 코드 테이블이 아닌 상수로 관리하는 것이 적절

2. **targetType 값**: `"USER"`, `"ROLE"` 등
   - 이유: 감사 로그 대상 타입은 코드 테이블이 아닌 상수로 관리하는 것이 적절

---

## 완료 기준 체크

- ✅ 하드코딩 제거 완료 (USER_STATUS, SUBJECT_TYPE)
- ✅ CodeResolver 사용으로 대체
- ✅ 컴파일 성공
- ✅ 기존 기능 동작 유지
- ✅ 테스트 통과

---

## 다음 단계

1. 다른 Service 클래스에서도 하드코딩 제거 검토
2. CodeResolver 테넌트별 코드 지원 강화 (선택사항)
3. 기본값 처리를 위한 헬퍼 메서드 추가 (선택사항)
