# Cursor IDE에서 Gradle 프로젝트 새로고침 방법

## 방법 1: Command Palette 사용 (가장 간단)

1. **Command Palette 열기**
   - `Cmd + Shift + P` (Mac) 또는 `Ctrl + Shift + P` (Windows/Linux)

2. **다음 명령어 중 하나 실행:**
   - `Java: Clean Java Language Server Workspace`
     - Java Language Server의 워크스페이스를 완전히 정리하고 재시작
   - `Java: Reload Projects`
     - 모든 Java 프로젝트를 다시 로드
   - `Java: Rebuild Projects`
     - 프로젝트를 다시 빌드

3. **권장 순서:**
   ```
   1. Java: Clean Java Language Server Workspace
   2. 잠시 대기 (5-10초)
   3. Java: Reload Projects
   ```

## 방법 2: Gradle 확장 기능 사용

1. **Command Palette 열기** (`Cmd + Shift + P`)

2. **다음 명령어 실행:**
   - `Gradle: Refresh Dependencies`
   - `Gradle: Refresh Gradle Project`

## 방법 3: 터미널에서 직접 실행

IDE의 통합 터미널에서:

```bash
# 프로젝트 루트로 이동
cd /Users/joonbinchoi/Work/dwp/dwp-backend

# Gradle 프로젝트 새로고침
./gradlew clean build -x test

# 또는 의존성만 새로고침
./gradlew --refresh-dependencies
```

## 방법 4: IDE 재시작

1. **Cursor 완전 종료**
2. **프로젝트 폴더 다시 열기**
3. IDE가 자동으로 Gradle 프로젝트를 인식

## 방법 5: Java Language Server 재시작

1. **Command Palette** (`Cmd + Shift + P`)
2. `Java: Restart Language Server` 실행
3. 프로젝트가 자동으로 다시 로드됨

## 문제 해결

### 여전히 오류가 발생하는 경우

1. **워크스페이스 정리:**
   ```bash
   # 프로젝트 루트에서
   rm -rf .gradle
   rm -rf build
   rm -rf */build
   rm -rf */*/build
   ```

2. **Java Language Server 캐시 삭제:**
   - Cursor 설정에서 Java Language Server 워크스페이스 캐시 위치 확인
   - 일반적으로: `~/Library/Application Support/Cursor/User/workspaceStorage/`

3. **Gradle 캐시 정리:**
   ```bash
   rm -rf ~/.gradle/caches
   ```

4. **프로젝트 다시 빌드:**
   ```bash
   ./gradlew clean build -x test
   ```

## 빠른 단축키

- `Cmd + Shift + P` → `Java: Clean Java Language Server Workspace`
- `Cmd + Shift + P` → `Java: Reload Projects`

## 확인 방법

새로고침이 성공했는지 확인:

1. **Problems 탭 확인** (하단 패널)
   - 오류가 사라졌는지 확인

2. **터미널에서 빌드 테스트:**
   ```bash
   ./gradlew :dwp-core:build
   ```

3. **IDE에서 클래스 import 확인:**
   - `SpringApplication` 같은 클래스가 자동완성되는지 확인

## 자주 발생하는 문제

### "Object cannot be resolved" 오류

이는 IDE의 클래스패스 문제입니다. 다음 순서로 해결:

1. `Java: Clean Java Language Server Workspace`
2. `Java: Reload Projects`
3. IDE 재시작

### 의존성이 인식되지 않는 경우

1. `Gradle: Refresh Dependencies` 실행
2. 터미널에서 `./gradlew --refresh-dependencies` 실행
3. 프로젝트 다시 빌드

## 팁

- **자동 새로고침**: Cursor는 파일 변경을 감지하면 자동으로 새로고침하지만, 때로는 수동 새로고침이 필요합니다.
- **빌드 후 새로고침**: 터미널에서 빌드를 실행한 후에는 IDE에서도 새로고침하는 것이 좋습니다.
- **의존성 추가 후**: `build.gradle`에 의존성을 추가한 후에는 반드시 새로고침이 필요합니다.
