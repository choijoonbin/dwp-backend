# SynapseX API/정책/감사 로그 검증 가이드

> 운영자가 즉시 확인 가능하도록 제공. CI에서 선택적 실행 가능.

---

## 1) 통합 테스트 (JUnit)

### 실행

```bash
./gradlew :services:synapsex-service:test --tests "com.dwp.services.synapsex.integration.*"
```

### 검증 항목

| 항목 | 테스트 클래스 | 설명 |
|------|---------------|------|
| tenant_id 없으면 400 | SynapseVerificationIntegrationTest | GET/POST 주요 엔드포인트 |
| audit_event_log 1건 이상 | SynapseVerificationIntegrationTest, AuditMandatoryEventIntegrationTest | case/status, action simulate/approve/execute |
| cross-tenant 차단 | SynapseVerificationIntegrationTest | tenant1이 tenant2 데이터 접근 시 404/400 |

### guardrail forbidden (403/409 + audit outcome=FAIL)

- Agent Tool API(`/api/synapse/agent-tools/**`) 경유 시 PolicyEngine이 DENIED 반환
- 일반 Action API는 guardrail 미적용 → 별도 Agent Tool 통합 테스트 필요

---

## 2) SQL 검증 스크립트

### 실행

```bash
# 기본 tenant_id=1
./scripts/run_synapse_verify.sh

# tenant_id 지정
TENANT_ID=2 ./scripts/run_synapse_verify.sh
```

### 직접 실행

```bash
psql -h localhost -U dwp_user -d dwp_aura -v tenantId=1 -f scripts/verify_synapse_audit.sql
```

### 검증 쿼리

| 번호 | 목적 |
|------|------|
| (1) | Audit 의무 이벤트 존재 여부 (최근 7일, event_category/event_type별 count) |
| (2) | Tenant scope 누락 탐지 (agent_case, agent_action, audit_event_log에서 tenant_id IS NULL 건수) |
| (3) | action simulate/execute 흐름 (event_category=ACTION, outcome/event_type별 count) |
| (4) | 정책 변경 감사 기록 (event_category=ADMIN, 최근 50건) |

---

## 3) CI 연동 (선택)

```yaml
# 예: GitHub Actions
- name: Synapse verification (optional)
  run: |
    ./scripts/run_synapse_verify.sh
  env:
    DB_HOST: ${{ secrets.DB_HOST }}
    DB_USER: ${{ secrets.DB_USER }}
    DB_PASSWORD: ${{ secrets.DB_PASSWORD }}
```
