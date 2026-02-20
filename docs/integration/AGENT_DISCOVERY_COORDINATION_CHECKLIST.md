# 에이전트 디스커버리 API 공동 체크리스트

> **목적**: 백엔드(Synapse)와 Aura 엔진 간 에이전트 디스커버리 API 협업 체크리스트  
> **작성일**: 2026-02-12

---

## 1. Domain 규격 (공통)

### 백엔드 정의
- **소스**: `dwp_aura.app_codes` 테이블 (`group_key = 'AGENT_DOMAIN'`)
- **마이그레이션**: `V54__agent_studio_app_codes.sql`
- **현재 정의된 도메인**:
  - `FINANCE`: 재무 감사
  - `HR`: 인사
  - `DEV`: 개발/엔지니어링

### 검증 로직
- `AgentStudioCodeValidator.validateDomain()`: `app_codes` 테이블에서 활성 코드만 허용
- 에이전트 생성/수정 시 `domain` 필드가 `app_codes`에 존재하는지 검증

### Aura 측 확인 사항
- [ ] Aura 코드에서 사용하는 도메인 값이 백엔드 `app_codes`와 일치하는가?
- [ ] `DEVOPS` 대신 `DEV`를 사용하는가? (또는 백엔드에 `DEVOPS` 추가 필요)
- [ ] 도메인 값이 대소문자 정확히 일치하는가? (예: `FINANCE` vs `finance`)

### 조치 사항
- 백엔드: `app_codes` 테이블이 단일 소스 오브 트루스(SoT)
- Aura: 백엔드 `GET /api/synapse/agents/catalog` 응답의 `domains` 배열을 참조하여 동기화

---

## 2. 상태 동기화 (백엔드)

### 현재 구현 상태
✅ **구현 완료**

**동작 방식**:
1. 에이전트 `is_active` 변경 시 (`AgentCommandService.update()`)
2. `notifyAuraRefresh()` 호출 → Aura 엔진에 캐시 무효화 신호 전송
3. 디스커버리 API (`GET /api/synapse/agents?discovery=true`)는 `is_active=true`만 반환

**코드 위치**:
- `AgentCommandService.update()`: `is_active` 변경 시 `notifyAuraRefresh()` 호출
- `AgentConfigQueryService.getAgentDiscovery()`: `findByTenantIdAndIsActiveTrueOrderByAgentIdAsc()` 사용

### Aura 측 확인 사항
- [ ] Aura가 `notifyAuraRefresh` 신호를 받으면 디스커버리 목록을 즉시 갱신하는가?
- [ ] 또는 주기적으로 `GET /api/synapse/agents?discovery=true`를 폴링하는가?
- [ ] 비활성화된 에이전트(`is_active=false`)가 목록에서 즉시 사라지는가?

### 조치 사항
- 백엔드: 이미 구현 완료 (refresh 신호 전송)
- Aura: refresh 신호 수신 시 디스커버리 목록 갱신 로직 구현 필요

---

## 3. 에러 핸들링 (Aura)

### 백엔드 에러 응답
**에러 코드**: `E3000` (`ENTITY_NOT_FOUND`)
- **발생 시점**: `GET /api/v1/agents/config?agent_key={agent_key}` 호출 시
- **조건**: 
  - 에이전트가 존재하지 않음
  - 또는 `is_active=false` (비활성 상태)

**응답 형식**:
```json
{
  "status": "ERROR",
  "code": "E3000",
  "message": "에이전트를 찾을 수 없거나 비활성 상태입니다.",
  "timestamp": "2026-02-12T22:22:05.186+09:00"
}
```

### Aura 측 확인 사항
- [ ] E3000 발생 시 Aura가 디스커버리 목록을 자동으로 갱신하는가?
- [ ] 갱신 후 동일 `agent_key`로 재시도하는가?
- [ ] 또는 에러를 로깅하고 다른 에이전트로 폴백하는가?

### 조치 사항
- 백엔드: 에러 응답 제공 (이미 구현 완료)
- Aura: E3000 발생 시 디스커버리 목록 갱신 및 재시도 로직 구현 필요

---

## 4. API 엔드포인트 요약

### 디스커버리 API
```
GET /api/synapse/agents?discovery=true
Header: X-Tenant-ID: {tenant_id}, Authorization: Bearer {token}

응답:
{
  "status": "SUCCESS",
  "data": {
    "agents": [
      {
        "agentKey": "finance_aura",
        "domain": "FINANCE",
        "description": "Finance Agent"
      }
    ]
  }
}
```

### 에이전트 설정 조회 API
```
GET /api/v1/agents/config?agent_key={agent_key}
Header: X-Tenant-ID: {tenant_id}, Authorization: Bearer {token}

성공 응답: AgentConfigResponseDto
에러 응답: E3000 (에이전트 없음 또는 비활성)
```

---

## 5. 체크리스트 요약

| 항목 | 체크 내용 | 백엔드 상태 | Aura 확인 필요 |
|------|----------|------------|--------------|
| Domain 규격 | 도메인 값 일치 | ✅ `app_codes` 관리 | ⚠️ Aura 코드 확인 |
| 상태 동기화 | `is_active` 변경 시 목록 갱신 | ✅ Refresh 신호 전송 | ⚠️ 신호 수신 로직 |
| 에러 핸들링 | E3000 발생 시 목록 갱신 | ✅ 에러 응답 제공 | ⚠️ 갱신 및 재시도 로직 |

---

## 6. 다음 단계

1. **Aura 측 확인**:
   - 도메인 값 일치 여부 확인 (`FINANCE`, `HR`, `DEV` vs `DEVOPS`)
   - Refresh 신호 수신 및 디스커버리 목록 갱신 로직 구현
   - E3000 발생 시 자동 갱신 및 재시도 로직 구현

2. **백엔드 추가 작업** (필요 시):
   - `DEVOPS` 도메인 추가 (Aura가 사용하는 경우)
   - 디스커버리 API 응답에 추가 메타데이터 포함 (필요 시)
