# IDE 빌드 경로 오류 해결 가이드

**작성일**: 2026-01-20  
**목적**: IDE에서 발생하는 "Cannot find the class file for java.lang.Object" 오류 해결

---

## 문제 증상

IDE Problems 탭에서 다음과 같은 오류가 발생할 수 있습니다:

```
The project was not built since its build path is incomplete. 
Cannot find the class file for java.lang.Object. 
Fix the build path then try building this project
```

또는

```
The type java.lang.Object cannot be resolved. 
It is indirectly referenced from required .class files
```

---

## 원인

이 오류는 IDE의 Java 빌드 경로 설정 문제로 발생합니다. 실제 Gradle 빌드는 정상적으로 작동하지만, IDE가 Java SDK를 제대로 인식하지 못하는 경우입니다.

---

## 해결 방법

### 방법 1: Gradle 프로젝트 새로고침 (권장)

**VS Code / Cursor:**
1. Command Palette (`Cmd+Shift+P` 또는 `Ctrl+Shift+P`) 열기
2. "Java: Clean Java Language Server Workspace" 실행
3. "Reload Window" 실행
4. 또는 터미널에서:
   ```bash
   ./gradlew clean build
   ```

**IntelliJ IDEA:**
1. `File` → `Invalidate Caches / Restart...`
2. `Invalidate and Restart` 선택
3. 또는 `File` → `Reload Gradle Project`

### 방법 2: Java SDK 확인

**VS Code / Cursor:**
1. Command Palette에서 "Java: Configure Java Runtime" 실행
2. Java 17 이상이 설정되어 있는지 확인
3. `JAVA_HOME` 환경 변수 확인:
   ```bash
   echo $JAVA_HOME
   ```

**IntelliJ IDEA:**
1. `File` → `Project Structure` → `Project`
2. `SDK`가 Java 17 이상으로 설정되어 있는지 확인
3. `File` → `Project Structure` → `Modules`
4. 각 모듈의 `Language level`이 17 이상인지 확인

### 방법 3: Gradle 빌드 실행

터미널에서 다음 명령어를 실행하여 프로젝트를 빌드합니다:

```bash
cd /Users/joonbinchoi/Work/dwp/dwp-backend
./gradlew clean build
```

빌드가 성공하면 IDE가 자동으로 인식합니다.

### 방법 4: IDE 재시작

위 방법들이 작동하지 않으면 IDE를 완전히 종료하고 다시 시작합니다.

---

## 확인 방법

1. **Gradle 빌드 확인**:
   ```bash
   ./gradlew build
   ```
   `BUILD SUCCESSFUL`이면 실제 빌드는 정상입니다.

2. **IDE Problems 탭 확인**:
   - 오류가 사라졌는지 확인
   - 경고만 남아있으면 정상 (일부 경고는 무시 가능)

---

## 참고 사항

### 무시해도 되는 경고

다음 경고는 기능에 영향을 주지 않으므로 무시해도 됩니다:

1. **YAML Unknown Property 경고**:
   - `jwt` 속성은 커스텀 속성으로, IDE 경고가 발생할 수 있으나 정상 동작합니다.
   - 주석으로 설명이 추가되어 있습니다.

2. **@MockBean Deprecated 경고**:
   - Spring Boot 3.4.x에서 `@MockBean`이 deprecated되었지만, 여전히 사용 가능합니다.
   - `@SuppressWarnings("removal")`로 경고를 억제할 수 있습니다.

---

## 문제가 지속되는 경우

1. **프로젝트 루트의 `.vscode/settings.json` 확인**:
   ```json
   {
     "java.configuration.updateBuildConfiguration": "automatic",
     "java.jdt.ls.java.home": "/path/to/java17"
   }
   ```

2. **Gradle Wrapper 확인**:
   ```bash
   ./gradlew --version
   ```
   Java 17 이상이 사용되는지 확인

3. **워크스페이스 재설정**:
   - 프로젝트 폴더를 IDE에서 닫고 다시 열기
   - 또는 새로운 워크스페이스로 프로젝트 임포트

---

**문서 작성일**: 2026-01-20  
**작성자**: DWP Backend Team
