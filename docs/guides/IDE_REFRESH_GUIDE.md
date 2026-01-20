# IDE 오류 해결 가이드

## 테스트 파일 import 오류 해결

`dwp-gateway` 모듈의 테스트 파일에서 import 오류가 발생하는 경우, IDE가 Gradle 의존성을 아직 인식하지 못한 것입니다.

### 해결 방법

#### VS Code / Cursor
1. **Command Palette** 열기: `Cmd+Shift+P` (Mac) / `Ctrl+Shift+P` (Windows)
2. 다음 명령어 실행:
   - `Java: Clean Java Language Server Workspace`
   - `Java: Reload Projects`

#### IntelliJ IDEA
1. **Gradle Tool Window** 열기
2. **Reload All Gradle Projects** 클릭
3. 또는 **File** → **Invalidate Caches** → **Invalidate and Restart**

#### Eclipse
1. 프로젝트 우클릭
2. **Gradle** → **Refresh Gradle Project**

### 확인 방법

```bash
# Gradle로 테스트 컴파일 확인
cd dwp-backend
./gradlew :dwp-gateway:compileTestJava

# 성공하면 IDE 인식 문제일 가능성이 높습니다
```

### 빌드 성공 확인

```bash
# 전체 빌드 (테스트 제외)
./gradlew clean build -x test

# 테스트 포함 빌드
./gradlew clean build
```

빌드가 성공하면 코드 자체는 문제가 없으며, IDE의 인덱싱 문제일 가능성이 높습니다.

---

**참고**: 테스트 의존성은 `dwp-gateway/build.gradle`에 이미 추가되어 있습니다:
- `spring-boot-starter-test`
- `spring-security-test`
- `reactor-test`
