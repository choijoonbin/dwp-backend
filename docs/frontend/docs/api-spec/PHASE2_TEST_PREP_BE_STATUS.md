# Phase2 테스트 준비 — 백엔드 구현 상태

Aura 팀 Phase2 테스트 전 확인 사항 기준, **백엔드 구현 상태** 요약입니다.

---

## 1. 백엔드 구현 완료 항목

| 항목 | 상태 | 비고 |
|------|------|------|
| 콜백 API | ✅ 구현됨 | `POST /api/synapse/internal/aura/callback` |
| Gateway 라우팅 | ✅ 설정됨 | `/api/synapse/internal/**` → synapsex |
| 콜백 스키마 처리 | ✅ 구현됨 | runId, status, finalResult(proposals 등) 저장 |
| BE 스트림 | ✅ 구현됨 | `GET /api/synapse/analysis-runs/{runId}/stream` |
| DEMO 모드 | ✅ 구현됨 | `SYNAPSE_DEMO_MODE=true` 시 Aura 미호출 |
| DEMO_OFF 대응 | ✅ 구현됨 | Aura `status=disabled` 시 run FAILED 처리 |
| Feign 예외 처리 | ✅ 구현됨 | run FAILED 저장 후 응답 |

**백엔드에서 미구현된 항목 없음.**

---

## 2. Aura 미구현 항목 (back.txt 기준)

| 항목 | back.txt 요구 | 현재 Aura | BE 준비 |
|------|---------------|-----------|---------|
| 트리거 응답 | 202 + JSON (status, runId, streamUrl) | SSE 스트리밍 반환 | ✅ 202+JSON 수신 대기 |
| 요청 body | runId (필수) 수신 | body 미사용 | ✅ runId 전송 중 |
| BE 콜백 | 분석 완료 후 POST 콜백 | 미구현 | ✅ 수신 API 준비됨 |
| 스트림 URL | GET /aura/.../stream?runId= | 미구현 | — (BE 스트림 사용 시 불필요) |
| 콜백 URL | DWP_GATEWAY_URL 등 | 미구현 | — (Aura 설정 항목) |

---

## 3. 테스트 시나리오별 준비 상태

| 시나리오 | BE | Aura | 비고 |
|----------|-----|------|------|
| FE → BE → BE 스트림 | ✅ 가능 | — | **지금 바로 테스트 가능** |
| FE → BE → BE DEMO 모드 | ✅ 가능 | — | `SYNAPSE_DEMO_MODE=true` 로 Aura 미호출 |
| BE → Aura 트리거 (202+JSON) | ✅ 대기 | 수정 필요 | Aura가 202+JSON 반환 시 연동 |
| BE → Aura 콜백 수신 | ✅ 수신 준비 | 구현 필요 | Aura가 콜백 발송 시 저장 |
| FE → Aura 스트림 직접 | — | 구현 필요 | BE 불관련 |

---

## 4. 단계별 테스트 권장

### 옵션 A: BE DEMO 모드로 FE/BE 단독 테스트

**방법 1: 실행 스크립트 (권장)**
```bash
./scripts/run-synapsex-demo.sh
```

**방법 2: Gradle 직접 실행**
```bash
SYNAPSE_DEMO_MODE=true ./gradlew :services:synapsex-service:bootRun --args='--spring.profiles.active=demo'
```

**방법 3: IDE (VS Code / Cursor)**
- Run > "SynapsexServiceApplication (DEMO)" 선택 후 기동
- 또는 기존 Synapsex 실행 시 `args`에 `--spring.profiles.active=demo` 추가, `env`에 `SYNAPSE_DEMO_MODE=true` 추가

- BE가 Aura를 호출하지 않음
- 트리거 즉시 `completeDemoRun()` 실행 → 샘플 result/proposal 생성
- FE → BE 트리거 → BE 스트림 → 분석 결과/액션 제안까지 전체 플로우 검증 가능

### 옵션 B: Aura 변경 후 E2E 테스트

Aura에서 아래를 반영한 뒤 BE ↔ Aura E2E 테스트 진행:

1. 트리거: `POST /analysis-runs` → **202 + JSON** (status, runId, streamUrl) 즉시 반환
2. 백그라운드 분석 완료 후 → `POST {DWP_GATEWAY_URL}/api/synapse/internal/aura/callback` 호출

---

## 5. 콜백 URL (Aura 설정용)

| 환경 | 콜백 URL |
|------|-----------|
| 로컬 | `http://localhost:8080/api/synapse/internal/aura/callback` |
| 운영 | `https://{gateway-host}/api/synapse/internal/aura/callback` |

Aura 환경변수 예: `DWP_GATEWAY_URL=http://localhost:8080`

---

## 6. 정리

- **백엔드**: 추가 구현 필요 없음.
- **BE DEMO 모드 테스트**: `SYNAPSE_DEMO_MODE=true` 로 바로 사용 가능.
- **BE ↔ Aura E2E**: Aura 트리거 202+JSON, 콜백 구현 후 진행 권장.

---

*작성일: 2025-02-09*
