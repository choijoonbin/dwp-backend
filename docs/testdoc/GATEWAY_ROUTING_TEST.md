# Gateway 라우팅 테스트 가이드

## 라우팅 흐름 분석

### 요청 경로: `http://localhost:8080/api/main/health`

1. **프론트엔드 요청**
   ```
   GET http://localhost:8080/api/main/health
   ```

2. **Gateway 라우팅 처리**
   - Path Predicate: `/api/main/**` → 매칭됨
   - Target URI: `http://localhost:8081` (dwp-main-service)
   - StripPrefix Filter: `/api/main/health` → `/main/health`로 변환
   - 최종 전달: `http://localhost:8081/main/health`

3. **Main Service 처리**
   - Controller: `@RequestMapping("/main")`
   - Endpoint: `@GetMapping("/health")`
   - 최종 경로: `/main/health` → 정상 처리

## Gateway 설정 검증

### 현재 설정 (application.yml)

```yaml
routes:
  - id: main-service
    uri: http://localhost:8081
    predicates:
      - Path=/api/main/**
    filters:
      - StripPrefix=1
```

### 라우팅 동작 설명

- **Path Predicate**: `/api/main/**` 패턴으로 요청을 매칭
- **URI**: `http://localhost:8081`로 요청을 전달
- **StripPrefix=1**: 경로의 첫 번째 세그먼트(`/api`)를 제거
  - 입력: `/api/main/health`
  - 출력: `/main/health`

## 테스트 방법

### 1. Gateway를 통한 접근 (권장)

```bash
# Gateway를 통한 요청
curl http://localhost:8080/api/main/health

# 예상 응답
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": "Main Service is running",
  "timestamp": "2024-01-01T12:00:00"
}
```

### 2. Main Service 직접 접근 (테스트용)

```bash
# Main Service에 직접 요청
curl http://localhost:8081/main/health

# 예상 응답 (동일)
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": "Main Service is running",
  "timestamp": "2024-01-01T12:00:00"
}
```

### 3. 브라우저에서 테스트

브라우저 주소창에 입력:
```
http://localhost:8080/api/main/health
```

## 서비스 실행 순서

1. **Docker Compose 실행** (인프라)
   ```bash
   docker-compose up -d
   ```

2. **Main Service 실행**
   ```bash
   ./gradlew :dwp-main-service:bootRun
   ```

3. **Gateway 실행**
   ```bash
   ./gradlew :dwp-gateway:bootRun
   ```

4. **테스트 요청**
   ```bash
   curl http://localhost:8080/api/main/health
   ```

## 라우팅 규칙 요약

| 프론트엔드 요청 경로 | Gateway 처리 | 최종 서비스 경로 | 서비스 |
|---------------------|-------------|-----------------|--------|
| `/api/main/**` | `/api` 제거 | `/main/**` | dwp-main-service (8081) |
| `/api/auth/**` | `/api` 제거 | `/auth/**` | dwp-auth-server (8000) |
| `/api/mail/**` | `/api` 제거 | `/mail/**` | mail-service (8082) |
| `/api/chat/**` | `/api` 제거 | `/chat/**` | chat-service (8083) |
| `/api/approval/**` | `/api` 제거 | `/approval/**` | approval-service (8084) |

## 문제 해결

### Gateway가 요청을 전달하지 않는 경우

1. **Gateway 로그 확인**
   ```bash
   # Gateway 로그에서 라우팅 정보 확인
   # DEBUG 레벨로 설정되어 있으므로 상세 로그 확인 가능
   ```

2. **Main Service 실행 확인**
   ```bash
   # Main Service가 8081 포트에서 실행 중인지 확인
   curl http://localhost:8081/main/health
   ```

3. **포트 충돌 확인**
   ```bash
   # 포트 사용 확인
   lsof -i :8080  # Gateway
   lsof -i :8081  # Main Service
   ```

### CORS 문제 발생 시

Gateway에 CORS 설정을 추가해야 할 수 있습니다. 필요 시 `CorsConfiguration`을 추가하세요.
