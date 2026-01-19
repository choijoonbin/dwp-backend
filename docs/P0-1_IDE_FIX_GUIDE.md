# P0-1 IDE 오류 해결 가이드

## 문제 상황

IDE에서 JWT 라이브러리 import 오류가 표시되지만, 실제 빌드는 성공합니다.

## 원인

IDE의 Gradle 캐시 문제로 인해 의존성을 인식하지 못하는 경우가 있습니다.

## 해결 방법

### 1. Gradle 프로젝트 새로고침 (권장)

**VS Code / Cursor:**
1. Command Palette (Cmd+Shift+P / Ctrl+Shift+P)
2. "Java: Clean Java Language Server Workspace" 실행
3. "Java: Reload Projects" 실행

**IntelliJ IDEA:**
1. Gradle 탭 열기
2. "Reload All Gradle Projects" 클릭
3. 또는 File → Invalidate Caches / Restart

### 2. Gradle 빌드 강제 실행

```bash
cd /Users/joonbinbinchoi/Work/dwp/dwp-backend
./gradlew clean build --refresh-dependencies
```

### 3. IDE 재시작

IDE를 완전히 종료한 후 다시 시작합니다.

## 확인 방법

빌드가 성공하면 코드는 정상입니다:

```bash
./gradlew :dwp-auth-server:build
```

**예상 출력:**
```
BUILD SUCCESSFUL
```

## 참고

- JWT 라이브러리 의존성은 `dwp-auth-server/build.gradle`에 정상적으로 추가되어 있습니다.
- 실제 컴파일 및 빌드는 성공하므로, IDE 표시 문제일 뿐입니다.
- 코드 자체는 문제가 없으므로 기능은 정상적으로 동작합니다.
