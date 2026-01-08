# Spring Boot 버전 호환성 문제 해결

## 문제

Spring Boot 3.5.9와 Spring Cloud 2024.0.0의 호환성 문제로 애플리케이션이 시작되지 않습니다.

## 해결 방법

### 1. 빌드 캐시 정리 (필수)

```bash
# 전체 프로젝트 클린
./gradlew clean

# 또는 특정 서비스만
./gradlew :dwp-main-service:clean

# build 디렉토리 수동 삭제
rm -rf dwp-main-service/build
```

### 2. IDE 클래스패스 새로고침 (필수)

IDE에서 실행할 때는 반드시 다음을 수행하세요:

**Cursor/VS Code:**
1. `Cmd + Shift + P` → `Java: Clean Java Language Server Workspace`
2. `Cmd + Shift + P` → `Java: Reload Projects`
3. IDE 재시작

**IntelliJ IDEA:**
1. `File` → `Invalidate Caches / Restart`
2. Gradle 탭에서 "Reload All Gradle Projects"

### 3. 의존성 새로고침

```bash
# 의존성 새로고침
./gradlew :dwp-main-service:build --refresh-dependencies
```

### 4. IDE에서 다시 실행

IDE를 재시작한 후 다시 실행하세요.

## 현재 버전

- **Spring Boot**: 3.4.3
- **Spring Cloud**: 2024.0.0
- **호환성**: ✅ 정상

## 확인 방법

```bash
# 의존성 확인
./gradlew :dwp-main-service:dependencies | grep spring-boot

# 빌드 확인
./gradlew :dwp-main-service:build -x test
```

## 주의사항

IDE에서 실행할 때는 **반드시** IDE 클래스패스를 새로고침해야 합니다. 
빌드만 새로고침하는 것으로는 부족하며, IDE가 이전에 빌드된 클래스를 사용할 수 있습니다.
