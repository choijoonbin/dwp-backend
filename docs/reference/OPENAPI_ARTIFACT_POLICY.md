# OpenAPI Artifact 정책 (C30, C31, C32)

## 목적
프론트엔드/백엔드 계약 변화가 몰래 들어오는 것을 차단하고, API 명세의 정합성을 보장

---

## OpenAPI 엔드포인트 표준

### 활성화된 서비스
| 서비스 | OpenAPI Docs | Swagger UI |
|--------|--------------|------------|
| dwp-auth-server | `http://localhost:8001/v3/api-docs` | `http://localhost:8001/swagger-ui.html` |
| dwp-main-service | `http://localhost:8081/v3/api-docs` | `http://localhost:8081/swagger-ui.html` |
| mail-service | `http://localhost:8082/v3/api-docs` | `http://localhost:8082/swagger-ui.html` |
| chat-service | `http://localhost:8083/v3/api-docs` | `http://localhost:8083/swagger-ui.html` |
| approval-service | `http://localhost:8084/v3/api-docs` | `http://localhost:8084/swagger-ui.html` |

**⚠️ 주의**: Gateway는 OpenAPI를 노출하지 않음 (Routing만 담당)

---

## Artifact 저장 정책

### 로컬 생성
```bash
# 각 서비스 기동 후 OpenAPI JSON 다운로드
curl http://localhost:8001/v3/api-docs > build/openapi/auth-server.json
curl http://localhost:8081/v3/api-docs > build/openapi/main-service.json
# ...
```

### 표준 경로
```
build/
  openapi/
    auth-server.json
    main-service.json
    mail-service.json
    chat-service.json
    approval-service.json
```

**⚠️ 주의**: `build/` 디렉토리는 `.gitignore`에 포함됨. Artifact는 CI에서만 생성/검증.

---

## CI/CD 통합 (향후 구성 시)

### GitHub Actions 예시 (C31)
```yaml
name: OpenAPI Artifact Generation

on:
  pull_request:
    branches: [main, develop]

jobs:
  openapi-artifact:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Build all services
        run: ./gradlew build -x test
      
      - name: Start auth-server
        run: |
          ./gradlew :dwp-auth-server:bootRun &
          sleep 30  # 서비스 기동 대기
      
      - name: Generate OpenAPI artifact
        run: |
          mkdir -p build/openapi
          curl http://localhost:8001/v3/api-docs > build/openapi/auth-server.json
      
      - name: Upload OpenAPI artifacts
        uses: actions/upload-artifact@v3
        with:
          name: openapi-specs
          path: build/openapi/*.json
```

---

## 계약 드리프트 감지 (C32)

### 옵션 1: openapi-diff 도구 (권장)
```bash
# 이전 버전과 현재 버전 비교
npm install -g openapi-diff
openapi-diff previous.json current.json --fail-on-incompatible
```

### 옵션 2: PR 체크리스트 강제 (최소 단계)
PR 템플릿에 다음 항목 추가:
```markdown
## API 계약 변경 (Breaking Change)
- [ ] API 응답 DTO 필드 추가/삭제/타입 변경 시 `docs/specs/API_CHANGELOG.md` 업데이트
- [ ] OpenAPI 문서 확인 (`/v3/api-docs`)
- [ ] 프론트엔드 팀에 변경 사항 공유
```

---

## OpenAPI 설정 표준

### application.yml
```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
    enabled: true
  swagger-ui:
    path: /swagger-ui.html
    enabled: true
    tags-sorter: alpha
    operations-sorter: alpha
  show-actuator: false  # Actuator 엔드포인트 숨김
```

### build.gradle
```gradle
dependencies {
    // SpringDoc OpenAPI
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0'
}
```

---

## 다음 단계
- [x] C30: springdoc-openapi 추가 + export 경로 표준화
- [ ] C31: CI에서 OpenAPI artifact 생성 (향후 CI/CD 구성 시)
- [x] C32: PR 템플릿에 계약 변경 체크리스트 추가

---

## 참고
- [SpringDoc OpenAPI 공식 문서](https://springdoc.org/)
- [OpenAPI Specification 3.0](https://swagger.io/specification/)
- [PR 체크리스트](.github/PULL_REQUEST_TEMPLATE.md)
