# IDE Java 오류 해결 가이드

## 문제 증상

IDE에서 다음과 같은 오류가 발생하는 경우:

```
The type java.lang.Object cannot be resolved. It is indirectly referenced from required .class files
Implicit super constructor Object() is undefined for default constructor. Must define an explicit constructor
```

## 원인

이 오류는 **IDE의 Java Language Server가 클래스패스를 제대로 인식하지 못해서** 발생합니다. 실제 코드나 Gradle 빌드에는 문제가 없습니다.

## 해결 방법

### 방법 1: Java Language Server 재시작 (가장 빠른 방법)

**VS Code:**
1. `Cmd+Shift+P` (Mac) 또는 `Ctrl+Shift+P` (Windows/Linux)
2. `Java: Clean Java Language Server Workspace` 실행
3. `Java: Reload Projects` 실행
4. IDE 재시작

**IntelliJ IDEA:**
1. `File` → `Invalidate Caches / Restart...`
2. `Invalidate and Restart` 선택

### 방법 2: Gradle 프로젝트 새로고침

**VS Code:**
1. `Cmd+Shift+P` → `Java: Reload Projects`
2. 또는 터미널에서:
   ```bash
   ./gradlew clean build
   ```

**IntelliJ IDEA:**
1. Gradle 탭 열기
2. 새로고침 버튼 클릭 (🔄)
3. 또는 `View` → `Tool Windows` → `Gradle` → 새로고침

### 방법 3: IDE 설정 확인

`.vscode/settings.json` 파일이 올바르게 설정되어 있는지 확인:

```json
{
  "java.configuration.updateBuildConfiguration": "automatic",
  "java.import.gradle.enabled": true,
  "java.import.gradle.wrapper.enabled": true,
  "java.import.gradle.java.home": "/path/to/java/home"
}
```

### 방법 4: Gradle Wrapper 재생성

```bash
cd /Users/joonbinchoi/Work/dwp/dwp-backend
./gradlew wrapper --gradle-version 8.5
```

### 방법 5: Java 버전 확인

IDE가 올바른 Java 버전을 사용하는지 확인:

```bash
# 터미널에서 확인
java -version

# Gradle이 사용하는 Java 버전 확인
./gradlew -v
```

## 검증

오류가 해결되었는지 확인:

1. **빌드 테스트:**
   ```bash
   ./gradlew :dwp-gateway:compileJava
   ```
   → `BUILD SUCCESSFUL`이 나와야 합니다.

2. **IDE에서 확인:**
   - 파일을 열었을 때 빨간 밑줄이 사라져야 합니다.
   - 자동완성이 정상적으로 작동해야 합니다.

## 추가 정보

- 이 오류는 **IDE의 문제**이며, 실제 코드나 빌드에는 영향을 주지 않습니다.
- Gradle 빌드가 성공하면 코드는 정상적으로 작동합니다.
- IDE를 재시작하거나 프로젝트를 새로고침하면 대부분 해결됩니다.

## 참고

- [VS Code Java Extension Guide](https://code.visualstudio.com/docs/java/java-project)
- [IntelliJ IDEA Gradle Integration](https://www.jetbrains.com/help/idea/gradle.html)
