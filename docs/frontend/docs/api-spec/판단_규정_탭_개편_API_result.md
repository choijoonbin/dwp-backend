# [BE 완료] 판단 규정 탭 개편 API — 구현 결과

> **요청 문서**: BE 전달용 판단 규정 탭 개편 API 요청  
> **완료일**: 2026-02-26

---

## 1. 구현 요약

`GET /api/synapse/cases/{caseId}` (및 `GET /api/v1/synapse/workbench/cases/{caseId}`) 응답에  
**`regulationCheckpoints`** (camelCase) / **`regulation_checkpoints`** (snake_case) 필드를 추가했습니다.

---

## 2. API 변경 사항

### 2.1 응답 스키마

| 필드 | 타입 | 설명 |
|------|------|------|
| `regulationCheckpoints` | `RegulationCheckpointDto[]` | 규정 적용 결과 배열. 있으면 FE 우선 사용. |
| `regulation_checkpoints` | (snake_case alias) | `@JsonAlias`로 동일 데이터 반환. |
| `logicCheckpoints` | (기존 유지) | 없으면 fallback. |

### 2.2 RegulationCheckpointDto 스키마

| 필드 | 타입 | 설명 |
|------|------|------|
| `ruleId` | string | 규정 식별자 (예: v2.0:ch13:art2:p1) |
| `version` | string | 규정 버전 |
| `chapter` | string | 장 정보 |
| `article` | string | 조 정보 |
| `clause` | string | 항 정보 |
| `title` | string | 규정 제목 |
| `status` | string | **COMPLIANT \| VIOLATION \| HOLD \| CONFLICT \| NEEDS_REVIEW** |
| `statusReason` | string | 상태 판정 사유 |
| `description` | string | 상세 설명 |
| `evidenceRefs` | string[] | citation_id 목록 (예: ["C1","C2"]) |
| `qualitySignals` | string[] | 분석 신뢰 신호(표시용 문구) |
| `applied` | boolean | 최종 판단에 반영 여부 |
| `priority` | number | 표시 우선순위 (낮을수록 상단) |

---

## 3. 동작 규칙

1. **Aura가 `regulation_checkpoints`를 전달할 때**
   - `decision_reason.regulation_checkpoints` 또는 `finalResult.regulation_checkpoints` (top-level)
   - BE는 `evidence_map_json`에 병합 저장 후 API 응답 시 파싱하여 반환

2. **Aura가 전달하지 않을 때 (Fallback)**
   - 기존 `logicCheckpoints`를 `RegulationCheckpointDto` 형식으로 변환
   - `clause`, `status`, `description` 매핑
   - `status` 매핑: `VIOLATED`→`VIOLATION`, `COMPLETED`→`COMPLIANT`, 기타→`NEEDS_REVIEW`

---

## 4. Aura 콜백 연동

Aura 분석 콜백에서 다음 형태로 전달 시 BE가 저장·반환합니다.

**방법 1**: `decision_reason` 내부
```json
{
  "finalResult": {
    "decision_reason": {
      "regulation_checkpoints": [
        {
          "ruleId": "v2.0:ch13:art2:p1",
          "version": "v2.0",
          "chapter": "제13장 감사, 보관 및 보고",
          "article": "제2조",
          "clause": "제1항",
          "title": "경과조치",
          "status": "HOLD",
          "statusReason": "위험유형-조항 정합성 부족",
          "evidenceRefs": ["C1"],
          "qualitySignals": ["위험유형-조항 불일치"],
          "applied": true,
          "priority": 1
        }
      ]
    }
  }
}
```

**방법 2**: `finalResult` top-level
```json
{
  "finalResult": {
    "regulation_checkpoints": [ ... ]
  }
}
```

---

## 5. FE 사용 가이드

- `regulationCheckpoints`가 있고 길이 > 0이면 해당 배열 사용
- 없거나 빈 배열이면 `logicCheckpoints` fallback
- `status`는 **COMPLIANT \| VIOLATION \| HOLD \| CONFLICT \| NEEDS_REVIEW** 5가지 표준값

---

## 6. 수정 파일

- `CaseDetailDto.java`: `RegulationCheckpointDto` 내부 클래스, `regulationCheckpoints` 필드 추가
- `CaseQueryService.java`: `buildRegulationCheckpoints()` 구현, `parseRegulationCheckpointsFromJson()`, `convertLogicCheckpointsToRegulationCheckpoints()` 추가
- `AuraCallbackPayload.java`: `FinalResult.regulationCheckpoints` 필드 추가
- `CaseAnalysisService.java`: `regulation_checkpoints` top-level 수신 시 `evidence_map_json` 병합 로직 추가
