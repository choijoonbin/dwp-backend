# Phase 2: Workbench Aggregator — Design

## 1. Phase 1 (Flyway V38) 최종 확인

- **파일**: `dwp-auth-server/src/main/resources/db/migration/V38__autonomous_workbench_menu_consolidation.sql`  
- **역할 매핑 (com_role_permissions)**  
  - **ADMIN**: VIEW, USE, EDIT, APPROVE, EXECUTE  
  - **SYNAPSEX_ADMIN**: VIEW, USE, EDIT, APPROVE, EXECUTE  
  - **SYNAPSEX_OPERATOR**: VIEW, USE, EDIT  
  - **SYNAPSEX_VIEWER**: VIEW  
  → V23의 `menu.autonomous-operations.cases` / `actions` / `anomalies` 패턴과 동일하게 적용됨.  
- 위 내용으로 Phase 1 스크립트 작성·검증 완료.

---

## 2. Pre-Check 질문 답변

### Q1. `agent_case`와 `agent_activity_log` 조인 시 성능을 위한 인덱스가 V19/V21에 충분히 반영되어 있는가?

**답변: 기본 조회·단일 케이스 타임라인에는 충분함. 대량 타임라인 조회 시 선택적 인덱스 권장.**

- **agent_case**  
  - V3: `ix_agent_case_doc`, `ix_agent_case_status(tenant_id, status, detected_at DESC)`  
  - V17: `ix_agent_case_tenant_status_severity(tenant_id, status, severity, detected_at DESC)`  
  - V21: `ux_agent_case_dedup_key`, `ix_agent_case_dedup_key`  
  - V22: `ix_agent_case_last_detect_run(last_detect_run_id)`  sv
  - Workbench: 테넌트 기준 케이스 목록 + case_id 단건 조회 → 기존 인덱스로 충분.

- **agent_activity_log** (V19)  
  - `ix_agent_activity_log_tenant_occurred(tenant_id, occurred_at DESC)`  
    → 전체 활동 스트림·최근 N건 조회에 적합.  
  - `ix_agent_activity_log_tenant_resource(tenant_id, resource_type, resource_id)`  
    → 특정 케이스(`resource_type='AGENT_CASE'`, `resource_id=caseId`)의 로그 필터에 사용.  
  - **권장 (선택)**: “케이스별 타임라인”을 `ORDER BY occurred_at DESC`로 자주 조회할 경우,  
    `(tenant_id, resource_type, resource_id, occurred_at DESC)` 복합 인덱스를 추후 마이그레이션에 추가하면 정렬 비용을 줄일 수 있음.

**결론**: 현재 인덱스만으로 Phase 2 Workbench Aggregator(목록 + 단일 케이스 상세·타임라인) 구현 가능. 대량/실시간 타임라인 트래픽이 늘어나면 위 복합 인덱스 추가 검토.

---

### Q2. 프론트엔드 실시간 타임라인 UI를 위해 로그를 `occurred_at` 역순으로 정렬한 전용 DTO를 둘 계획이 있는가?

**답변: 예. `occurred_at` 역순 정렬을 전제로 한 타임라인 전용 DTO를 둡니다.**

- 기존 `AgentActivityItemDto`는 대시보드용으로 `ts` 등을 사용.  
- Workbench는 **타임라인 UI**에 맞춰 다음을 적용할 계획:
  - **필드**: `occurredAt`(ISO-8601), `stage`, `eventType`, `resourceType`, `resourceId`, `actorDisplayName`, `metadataJson` 등.
  - **정렬**: 서비스 계층에서 `occurred_at DESC`로 조회·반환.
  - **전용 DTO**: `WorkbenchTimelineItemDto` (아래 3절 참고).  
  → FE는 이 DTO 리스트를 그대로 타임라인 컴포넌트에 바인딩하면 됨.

---

## 3. Workbench Controller 및 관련 DTO 설계

### 3.1 API 요약

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/synapse/workbench` | 통합 워크벤치: 케이스 목록(마스터) + 선택 시 분석 결과·활동 로그 포함 |
| GET | `/api/synapse/workbench/cases/{caseId}` | 단일 케이스 상세: agent_case + case_analysis_result(최신) + agent_activity_log(occurred_at DESC) |

- 목록은 기존 Cases API와 유사한 필터(tenant, status, severity, caseType, 기간 등) 재사용 가능.  
- 상세 한 번 호출로 “케이스 마스터 + 분석 결과 + 타임라인 로그”를 한 번에 반환하는 Aggregator 형태로 설계.

### 3.2 응답 DTO 설계

#### WorkbenchSummaryResponseDto (GET /workbench — 목록/요약)

```java
/** GET /api/synapse/workbench 응답: 케이스 마스터 목록 + (선택) 요약 통계 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkbenchSummaryResponseDto {
    private PageInfo pageInfo;
    private List<WorkbenchCaseRowDto> cases;  // agent_case 기반 행
    private WorkbenchSummaryStatsDto summary; // optional: 건수/상태별 집계
}
```

#### WorkbenchCaseRowDto (목록 1행)

- `CaseListRowDto`와 동일하거나, Workbench 전용으로 필요한 필드만 포함  
  (caseId, detectedAt, caseType, severity, status, reasonTextShort, assigneeUserId, amount, currency, docKeys, partySummary 등).

#### WorkbenchCaseDetailResponseDto (GET /workbench/cases/{caseId} — Aggregator 응답)

```java
/** GET /api/synapse/workbench/cases/{caseId} — 한 번의 호출로 케이스 + 분석 결과 + 타임라인 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkbenchCaseDetailResponseDto {
    private WorkbenchCaseDetailDto case_;           // agent_case 상세
    private CaseAnalysisResultDto latestAnalysis;   // case_analysis_result (최신 run 1건, 없으면 null)
    private List<WorkbenchTimelineItemDto> timeline; // agent_activity_log, occurred_at DESC
}
```

#### WorkbenchTimelineItemDto (타임라인 전용, occurred_at 역순)

```java
/** 실시간 타임라인 UI용. 서비스에서 occurred_at DESC 정렬 후 반환 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkbenchTimelineItemDto {
    private Long activityId;
    private Instant occurredAt;      // FE 타임라인 정렬 기준
    private String stage;
    private String eventType;
    private String resourceType;
    private String resourceId;
    private String actorAgentId;
    private Long actorUserId;
    private String actorDisplayName;
    private Map<String, Object> metadataJson;
}
```

- 서비스 계층: `agent_activity_log`를 `tenant_id`, `resource_type='AGENT_CASE'`, `resource_id=caseId`로 필터 후 `ORDER BY occurred_at DESC`로 조회해 이 DTO 리스트로 매핑.

#### CaseAnalysisResultDto (기존 또는 Workbench 전용)

- `CaseAnalysisResult` 엔티티 기준: runId, score, severity, reasonText, confidenceJson, evidenceJson, similarJson, ragRefsJson, createdAt.  
- 이미 있다면 재사용, 없으면 `dto/workbench/CaseAnalysisResultDto.java` 등으로 정의.

### 3.3 WorkbenchController (제안)

```java
@RestController
@RequestMapping("/synapse/workbench")
@RequiredArgsConstructor
public class WorkbenchController {

    private final WorkbenchQueryService workbenchQueryService;

    @GetMapping
    public ApiResponse<WorkbenchSummaryResponseDto> getWorkbench(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String caseType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        WorkbenchSummaryResponseDto body = workbenchQueryService.getWorkbenchSummary(tenantId, status, severity, caseType, from, to, page, size);
        return ApiResponse.success(body);
    }

    @GetMapping("/cases/{caseId}")
    public ApiResponse<WorkbenchCaseDetailResponseDto> getCaseDetail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long caseId) {
        WorkbenchCaseDetailResponseDto body = workbenchQueryService.getCaseDetailWithTimeline(tenantId, caseId);
        return ApiResponse.success(body);
    }
}
```

- Guard: 메뉴 권한(`menu.autonomous-operations.workbench`)은 Gateway/인증에서 처리하거나, 필요 시 `@PreAuthorize` 등으로 VIEW 체크.

### 3.4 WorkbenchQueryService (Aggregator 로직)

- **getWorkbenchSummary(tenantId, filters, page, size)**  
  - `agent_case`를 tenant + 필터로 조회(기존 CaseQueryService/Repository 재사용 가능).  
  - `WorkbenchCaseRowDto` 리스트 + PageInfo, (선택) 요약 통계 반환.

- **getCaseDetailWithTimeline(tenantId, caseId)**  
  1. **agent_case**: caseId + tenantId로 1건 조회 → `WorkbenchCaseDetailDto`.  
  2. **case_analysis_result**: `case_analysis_run.case_id = caseId` 인 run의 최신 1건 조회 후 result 조회 → `CaseAnalysisResultDto`.  
  3. **agent_activity_log**: `tenant_id, resource_type='AGENT_CASE', resource_id=String.valueOf(caseId)` 조건, `ORDER BY occurred_at DESC`, 상위 N건(예: 100) → `WorkbenchTimelineItemDto` 리스트.  
  - 1~3을 하나의 `WorkbenchCaseDetailResponseDto`로 묶어 반환.

- 트랜잭션: 읽기 전용 `@Transactional(readOnly = true)`.  
- tenant_id 검증: 모든 조회에 tenant_id 필수 적용(데이터 격리).

### 3.5 패키지/파일 제안

```
services/synapsex-service/
├── controller/
│   └── WorkbenchController.java
├── dto/
│   └── workbench/
│       ├── WorkbenchSummaryResponseDto.java
│       ├── WorkbenchCaseRowDto.java
│       ├── WorkbenchCaseDetailResponseDto.java
│       ├── WorkbenchCaseDetailDto.java
│       ├── WorkbenchTimelineItemDto.java
│       └── WorkbenchSummaryStatsDto.java (optional)
├── service/
│   └── workbench/
│       ├── WorkbenchQueryService.java
│       └── WorkbenchMapper.java (Entity → DTO)
```

- Repository: 기존 `AgentCaseRepository`, `CaseAnalysisRunRepository`, `CaseAnalysisResultRepository`, `AgentActivityLogRepository` 활용.  
- `AgentActivityLogRepository`에 `findByTenantIdAndResourceTypeAndResourceIdOrderByOccurredAtDesc(tenantId, "AGENT_CASE", String.valueOf(caseId), Pageable)` 또는 QueryDSL 메서드 추가.

---

## 4. 인덱스 권장 (선택, 추후 마이그레이션)

- **agent_activity_log**  
  - `(tenant_id, resource_type, resource_id, occurred_at DESC)`  
  - 목적: 케이스별 타임라인 조회 시 정렬 비용 제거.

---

## 5. 요약

- **Phase 1**: V38 스크립트 및 ADMIN/SYNAPSEX_* 역할 매핑 최종 확인 완료.  
- **Phase 2 Pre-Check**:  
  - 인덱스는 현재로도 충분하며, 타임라인 전용 복합 인덱스는 선택 사항.  
  - `occurred_at` 역순 정렬 타임라인용 전용 DTO(`WorkbenchTimelineItemDto`) 설계 반영.  
- **Phase 2 설계**:  
  - `WorkbenchController` (GET /workbench, GET /workbench/cases/{caseId}),  
  - Aggregator 응답 DTO 및 `WorkbenchTimelineItemDto`,  
  - `WorkbenchQueryService`에서 agent_case + case_analysis_result + agent_activity_log를 한 번에 조회·조합하는 구조로 제안.
