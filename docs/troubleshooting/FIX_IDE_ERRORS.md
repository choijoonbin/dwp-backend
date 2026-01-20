# IDE 오류 해결 가이드

## 현재 상황

실제 Gradle 빌드는 성공했지만, IDE에서 다음 오류가 표시되고 있습니다:

### 심각한 오류 (Error)
- `java.lang.Object cannot be resolved` - IDE 클래스패스 문제
- `The project was not built since its build path is incomplete` - IDE 프로젝트 인식 문제

### 경고 (Warning)
- Spring Boot 3.2.x 지원 종료 알림 (정보성)
- 사용하지 않는 import (HttpStatus - 실제로는 사용 중)
- Null type safety 경고 (정보성)

## 해결 방법

### 1단계: IDE 클래스패스 문제 해결 (가장 중요)

**Cursor IDE에서:**

1. **Command Palette 열기** (`Cmd + Shift + P`)

2. **다음 명령어를 순서대로 실행:**
   ```
   Java: Clean Java Language Server Workspace
   ```
   - 완료 후 10-15초 대기
   
   ```
   Java: Reload Projects
   ```
   - 모든 Java 프로젝트가 다시 로드됩니다

3. **여전히 오류가 있다면:**
   ```
   Java: Restart Language Server
   ```

### 2단계: 프로젝트 빌드 확인

터미널에서:
```bash
cd /Users/joonbinchoi/Work/dwp/dwp-backend
./gradlew clean build -x test
```

빌드가 성공하면 코드 자체는 문제가 없습니다.

### 3단계: IDE 캐시 정리 (필요시)

```bash
# 프로젝트 루트에서
rm -rf .gradle
rm -rf build
rm -rf */build
rm -rf */*/build

# Java Language Server 캐시 위치 확인
# 일반적으로: ~/Library/Application Support/Cursor/User/workspaceStorage/
```

### 4단계: IDE 재시작

1. Cursor 완전 종료
2. 프로젝트 폴더 다시 열기
3. IDE가 자동으로 Gradle 프로젝트 인식

## 오류별 해결 방법

### "java.lang.Object cannot be resolved"

이는 IDE가 Java 기본 클래스를 찾지 못하는 문제입니다.

**해결:**
1. `Java: Clean Java Language Server Workspace` 실행
2. `Java: Reload Projects` 실행
3. IDE 재시작

**확인:**
- `File` → `Preferences` → `Settings`에서 Java 버전 확인
- 프로젝트가 Java 17로 설정되어 있는지 확인

### "The project was not built since its build path is incomplete"

IDE가 프로젝트 빌드 경로를 제대로 인식하지 못하는 문제입니다.

**해결:**
1. 터미널에서 빌드 실행: `./gradlew build`
2. IDE에서 `Java: Reload Projects` 실행
3. 프로젝트 폴더를 닫고 다시 열기

### Spring Boot 버전 경고

Spring Boot 3.2.x 지원이 종료되었다는 경고입니다. 정보성 경고이므로 무시해도 됩니다.

**업그레이드하려면:**
```gradle
// build.gradle
plugins {
    id 'org.springframework.boot' version '3.5.9' apply false
}
```

### 사용하지 않는 import 경고

`HttpStatus` import가 사용되지 않는다는 경고가 있지만, 실제로는 `ErrorCode`에서 사용되고 있습니다. 이는 IDE의 잘못된 분석일 수 있습니다.

**확인:**
- 실제로 사용 중이면 무시해도 됩니다
- 정말 사용하지 않는다면 제거

## 빠른 해결 체크리스트

- [ ] `Java: Clean Java Language Server Workspace` 실행
- [ ] `Java: Reload Projects` 실행
- [ ] 터미널에서 `./gradlew build` 성공 확인
- [ ] IDE 재시작
- [ ] 오류가 계속되면 Java Language Server 캐시 삭제

## 참고

- **실제 빌드가 성공**했다면 코드 자체는 문제가 없습니다
- IDE 오류는 클래스패스 인식 문제일 가능성이 높습니다
- 대부분의 경우 `Java: Clean Java Language Server Workspace` + `Java: Reload Projects`로 해결됩니다
