# IDE 캐시 문제 해결 가이드

## 문제 상황

IDE에서 실행할 때 Spring Boot 3.5.9가 사용되는 문제는 IDE가 이전에 빌드된 클래스를 캐시하고 있기 때문입니다.

## 해결 방법 (순서대로 실행)

### 1단계: Gradle 빌드 캐시 정리

```bash
cd /Users/joonbinchoi/Work/dwp/dwp-backend

# 전체 클린
./gradlew clean

# 특정 서비스 클린
./gradlew :dwp-main-service:clean

# build 디렉토리 수동 삭제
rm -rf dwp-main-service/build
```

### 2단계: IDE 클래스패스 완전 정리 (가장 중요!)

**Cursor/VS Code:**
1. `Cmd + Shift + P` (또는 `Ctrl + Shift + P`)
2. `Java: Clean Java Language Server Workspace` 실행
3. 완료 후 10-15초 대기
4. `Java: Reload Projects` 실행
5. **IDE 완전 재시작** (Cursor 종료 후 다시 열기)

**IntelliJ IDEA:**
1. `File` → `Invalidate Caches / Restart`
2. `Invalidate and Restart` 선택
3. Gradle 탭에서 "Reload All Gradle Projects"

### 3단계: 새로 빌드

```bash
# 의존성 새로고침과 함께 빌드
./gradlew :dwp-main-service:build --refresh-dependencies -x test
```

### 4단계: IDE에서 다시 실행

IDE를 재시작한 후 `MainServiceApplication`을 다시 실행하세요.

## 확인 방법

빌드 후 다음 명령어로 Spring Boot 버전 확인:

```bash
./gradlew :dwp-main-service:dependencies --configuration runtimeClasspath | grep spring-boot-starter-web
```

출력 예시:
```
+--- org.springframework.boot:spring-boot-starter-web -> 3.4.3
```

## 중요 사항

⚠️ **IDE에서 실행할 때는 반드시 IDE 클래스패스를 새로고침해야 합니다!**

- Gradle 빌드만 새로고침하는 것으로는 부족합니다
- IDE가 이전에 빌드된 `.class` 파일을 사용할 수 있습니다
- `Java: Clean Java Language Server Workspace`는 필수입니다

## 현재 설정

- **Spring Boot**: 3.4.3
- **Spring Cloud**: 2024.0.0
- **호환성**: ✅ 정상

## 문제가 계속되면

1. IDE 완전 종료
2. 프로젝트 폴더 닫기
3. `rm -rf .gradle build */build */*/build` (빌드 캐시 완전 삭제)
4. IDE 재시작
5. 프로젝트 다시 열기
6. `Java: Clean Java Language Server Workspace` 실행
