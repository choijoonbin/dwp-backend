# IDE 설정 가이드

## 문제: "SpringApplication cannot be resolved"

이 오류는 IDE가 Gradle 프로젝트의 의존성을 제대로 인식하지 못해서 발생합니다.

## 해결 방법

### 방법 1: IDE에서 Gradle 프로젝트 새로고침 (권장)

**VS Code / Cursor:**
1. Command Palette 열기 (`Cmd+Shift+P` 또는 `Ctrl+Shift+P`)
2. "Java: Clean Java Language Server Workspace" 실행
3. "Java: Reload Projects" 실행
4. 또는 터미널에서:
   ```bash
   # 프로젝트 루트에서
   ./gradlew clean build -x test
   ```

**IntelliJ IDEA:**
1. Gradle 탭 열기 (오른쪽 사이드바)
2. "Reload All Gradle Projects" 클릭 (새로고침 아이콘)
3. 또는 `File` → `Invalidate Caches / Restart`

**Eclipse:**
1. 프로젝트 우클릭 → `Gradle` → `Refresh Gradle Project`

### 방법 2: 수동으로 의존성 다운로드

SSL 인증서 문제가 있는 경우, 다음을 시도하세요:

```bash
# Gradle 캐시 확인
ls -la ~/.gradle/caches/

# 오프라인 모드로 빌드 시도 (이미 다운로드된 의존성 사용)
./gradlew build --offline -x test
```

### 방법 3: IDE에서 직접 실행 (빌드 후)

프로젝트가 빌드되면 IDE에서 직접 실행할 수 있습니다:

1. `AuthServerApplication.java` 파일 열기
2. `main` 메서드 옆의 실행 버튼 클릭
3. 또는 `Run` → `Run 'AuthServerApplication'`

### 방법 4: Gradle Task로 실행

IDE의 터미널에서:

```bash
# Auth Server 실행
./gradlew :dwp-auth-server:bootRun
```

## 현재 프로젝트 상태 확인

```bash
# 프로젝트 구조 확인
./gradlew projects

# 의존성 확인
./gradlew :dwp-auth-server:dependencies
```

## SSL 인증서 문제 해결

네트워크 환경에 따라 SSL 인증서 문제가 발생할 수 있습니다:

1. **회사 프록시 환경인 경우**
   - 프록시 설정 확인
   - 인증서를 Java truststore에 추가

2. **로컬 네트워크 문제**
   - 인터넷 연결 확인
   - DNS 설정 확인

3. **임시 해결책**
   ```bash
   # Gradle wrapper 속성 수정 (권장하지 않음)
   # gradle/wrapper/gradle-wrapper.properties
   # distributionUrl을 http로 변경 (보안상 권장하지 않음)
   ```

## 권장 작업 순서

1. **IDE에서 Gradle 프로젝트 새로고침**
2. **프로젝트 빌드** (가능한 경우)
3. **메인 클래스 직접 실행**

## 대안: Docker로 실행

만약 로컬 환경에서 계속 문제가 발생한다면, Docker를 사용할 수도 있습니다:

```bash
# Dockerfile 생성 후
docker build -t dwp-auth-server .
docker run -p 8000:8000 dwp-auth-server
```
