# GET /api/synapse/entities/parties — 거래처 목록 API 명세

> **대상**: 프론트엔드  
> **작성일**: 2026-01-29  
> **관련 문서**: `docs/frontend/docs/api-spec/SYNAPSEX_API_SPECIFICATION.md` (1.3 Entity Hub)

---

## 1. API 개요

| 항목 | 내용 |
|------|------|
| **Method** | GET |
| **Path** | `/api/synapse/entities/parties` |
| **별칭** | `/api/synapse/entities` (동일 동작, 경로 충돌 방지용 `/parties` 권장) |
| **용도** | 거래처(Business Partner) 목록 조회, 페이징·필터·검색 지원 |

### 필수 헤더
| 헤더 | 타입 | 필수 |
|------|------|------|
| X-Tenant-ID | Long | ✅ |
| Authorization | Bearer {JWT} | ✅ |

---

## 2. Query 파라미터

| 파라미터 | 타입 | 기본값 | 필수 | 설명 |
|----------|------|--------|------|------|
| type | string | - | - | 거래처 유형: `VENDOR` \| `CUSTOMER` |
| bukrs | string | - | - | 회사코드 (Scope 내 활성 회사코드만 유효) |
| country | string | - | - | 국가코드 (3자리, bp_party.country와 정확 일치) |
| riskMin | double | - | - | 리스크 점수 최소값 (0~100) |
| riskMax | double | - | - | 리스크 점수 최대값 (0~100) |
| hasOpenItems | boolean | - | - | `true` 시 오픈아이템 보유 거래처만 |
| q | string | - | - | 텍스트 검색: `name_display` OR `party_code` (대소문자 무시, 부분 일치) |
| page | int | 0 | - | 0-based 페이지 |
| size | int | 20 | - | 페이지 크기 (최대 100) |
| sort | string | updatedAt,desc | - | 정렬: `field` 또는 `field,asc` / `field,desc` |

### sort 지원 필드
| field | 설명 |
|-------|------|
| partyCode | 거래처코드 |
| nameDisplay | 표시명 |
| updatedAt | 수정일시 (기본) |

---

## 3. 검색 조건 상세

### 3.1 type (거래처 유형)

- **값**: `VENDOR` | `CUSTOMER` (대문자)
- **백엔드 코드 API**: ❌ 없음 — 프론트에서 고정 옵션으로 제공
- **Selectbox 옵션 예시**:
  ```json
  [
    { "value": "", "label": "전체" },
    { "value": "VENDOR", "label": "공급업체" },
    { "value": "CUSTOMER", "label": "고객" }
  ]
  ```

### 3.2 country (국가코드)

- **형식**: 3자리 문자열 (예: KOR, USA, DEU)
- **백엔드 코드 API**: ❌ 없음 — 현재 별도 API 미제공
- **대안**:
  - **A) 하드코딩**: ISO 3166-1 alpha-3 등 표준 국가 목록 사용
  - **B) API 추가 요청**: `GET /api/synapse/entities/parties/filter-options` 등으로 `distinct country` 목록 제공 (추후 검토)

### 3.3 bukrs (회사코드)

- **백엔드 코드 API**: ✅ 있음
- **API**: `GET /api/synapse/admin/tenant-scope/company-codes?profileId=`
- **또는**: `GET /api/synapse/admin/catalog/company-codes` (Admin 권한)
- Selectbox 옵션은 위 API 응답 사용

### 3.4 q (텍스트 검색)

- **검색 대상**: `name_display` OR `party_code`
- **동작**: 부분 일치, 대소문자 무시 (ILIKE)
- **예시**: `q=test` → name_display에 "test" 포함 OR party_code에 "test" 포함

---

## 4. 응답 형식

### 4.1 성공 응답 (200)

```json
{
  "status": "SUCCESS",
  "data": {
    "items": [
      {
        "partyId": 1,
        "type": "VENDOR",
        "name": "Test Vendor Co",
        "country": "KOR",
        "riskScore": 45.2,
        "riskTrend": "STABLE",
        "openItemsCount": 3,
        "openItemsTotal": "1500000",
        "overdueCount": 1,
        "overdueTotal": "500000",
        "recentAnomaliesCount": 0,
        "lastChangedAt": "2026-01-29T10:00:00Z"
      }
    ],
    "total": 42,
    "pageInfo": {
      "page": 0,
      "size": 20,
      "hasNext": true
    }
  }
}
```

### 4.2 필드 설명

| 필드 | 타입 | 설명 |
|------|------|------|
| partyId | Long | 거래처 ID |
| type | string | VENDOR \| CUSTOMER |
| name | string | 표시명 (name_display) |
| country | string | 국가코드 (3자리) |
| riskScore | double | 리스크 점수 (0~100) |
| riskTrend | string | STABLE 등 |
| openItemsCount | int | 미결항목 건수 |
| openItemsTotal | string | 미결항목 총액 |
| overdueCount | int | 연체 건수 |
| overdueTotal | string | 연체 총액 |
| recentAnomaliesCount | int | 최근 이상 케이스 건수 |
| lastChangedAt | string | 마지막 변경 시각 (ISO-8601) |

---

## 5. 호출 예시

### 검색조건 없이 호출 (프론트 현재 사용 방식)
```
GET /api/synapse/entities/parties?page=0&size=20
```
→ 전체 거래처 목록, 기본 정렬(updatedAt desc)

### 필터 적용 호출
```
GET /api/synapse/entities/parties?type=VENDOR&country=KOR&q=test&page=0&size=20
```

---

## 6. 코드/필터 옵션 요약

| 검색조건 | Selectbox 데이터 출처 | 비고 |
|----------|----------------------|------|
| type | 프론트 고정 (VENDOR, CUSTOMER) | 백엔드 API 없음 |
| country | 프론트 고정(ISO 목록) 또는 추후 API | 백엔드 API 없음 |
| bukrs | `GET /api/synapse/admin/tenant-scope/company-codes` | Admin API |
| q | 사용자 입력 (텍스트) | name_display OR party_code 검색 |

---

## 7. 관련 API 정의 위치

| 문서 | 경로 | 내용 |
|------|------|------|
| SYNAPSEX_API_SPECIFICATION.md | `docs/frontend/docs/api-spec/` | 1.3 Entity Hub (전체 Entities API) |
| 설계부족_PHASE1_DATA_TRUST_API.md | `docs/synapse_front/` | Phase1 설계 참고 |
