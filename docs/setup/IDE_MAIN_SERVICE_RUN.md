# Main Service IDE 실행 가이드

## 문제 상황

IDE에서 `MainServiceApplication`을 실행할 때 다음 에러가 발생할 수 있습니다:

```
java.lang.NoClassDefFoundError: io/jsonwebtoken/ExpiredJwtException
```

## 원인

IDE의 실행 설정에서 클래스패스에 JWT 라이브러리가 포함되지 않았을 수 있습니다.

## 해결 방법

### 방법 1: Gradle bootRun 사용 (권장)

IDE에서 직접 실행하는 대신, 터미널에서 Gradle의 `bootRun` 태스크를 사용하세요:

```bash
./gradlew :dwp-main-service:bootRun
```

또는 프로필 지정:

```bash
./gradlew :dwp-main-service:bootRun --args='--spring.profiles.active=dev'
```

### 방법 2: IDE 실행 설정 수정

#### IntelliJ IDEA / Cursor

1. **Run Configuration 확인**
   - `Run` → `Edit Configurations...`
   - `MainServiceApplication` 실행 설정 선택

2. **Use classpath of module 확인**
   - `Use classpath of module: dwp-main-service` 선택
   - 또는 `Use Gradle` 옵션 활성화

3. **Gradle 실행 설정**
   - `Run` → `Edit Configurations...`
   - `Before launch` 섹션에서 `Build` 대신 `Run Gradle task` 추가
   - Task: `:dwp-main-service:classes`

#### VS Code / Cursor

1. **`.vscode/launch.json` 파일 확인**
   - 프로젝트 루트에 `.vscode/launch.json` 파일이 생성되어 있습니다.
   - "MainServiceApplication (Gradle)" 설정을 사용하세요.

2. **Gradle Tasks 실행 (권장)**
   - 터미널에서 `./gradlew :dwp-main-service:bootRun` 실행
   - 또는 VS Code의 Java Extension이 자동으로 Gradle을 사용합니다.

3. **수동 클래스패스 설정 (비권장)**
   - IDE가 자동으로 클래스패스를 설정하지 않는 경우에만 사용
   - `launch.json`에서 `classPaths`를 명시적으로 설정

### 방법 3: JAR 파일로 실행

빌드된 JAR 파일을 직접 실행:

```bash
./gradlew :dwp-main-service:build
java -jar dwp-main-service/build/libs/dwp-main-service-1.0.0.jar
```

## 확인

서비스가 정상적으로 시작되면 다음 명령으로 확인:

```bash
curl http://localhost:8081/main/health
```

예상 응답:
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": "Main Service is running",
  "timestamp": "2026-01-16T15:08:34.641272",
  "success": true
}
```

## 참고

- JWT 라이브러리는 `build.gradle`에 `implementation`으로 추가되어 있습니다.
- `bootJar`로 빌드된 JAR에는 모든 의존성이 포함됩니다.
- IDE 실행 시 클래스패스 문제가 발생하면 `bootRun` 태스크 사용을 권장합니다.
