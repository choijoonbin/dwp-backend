# agent_tool_inventory 시드용 도구 정보 (Aura → 백엔드)

Aura에 등록되어 있는 모든 도구의 이름·설명·파라미터를 정리했습니다.  
백엔드에서 에이전트별로 골라 매핑(agent_tool_mapping)할 수 있습니다.  
`tool_name`은 Aura 코드의 함수명과 **완전 동일**해야 합니다.

---

## 에이전트별 도구 매핑 (agent_tool_mapping 등록 참고)

| 에이전트(agent_key) | domain | 사용 도구 목록 (tool_names) |
|---------------------|--------|-----------------------------|
| **finance_aura** | FINANCE | get_case, search_documents, get_document, get_entity, get_open_items, get_lineage, web_search, simulate_action, propose_action, execute_action |
| **dev_aura** (선택) | DEV | git_diff, git_log, git_status, git_show_file, git_branch_list, github_get_pr, github_list_prs, github_get_pr_diff, github_get_file |

- 위 목록은 선택 가능 풀. 스튜디오에서 에이전트별로 일부만 활성화해 매핑해도 됨.
- **agent_tool_inventory**에는 아래 **전체 19개**를 먼저 등록한 뒤, 에이전트마다 위 표를 참고해 agent_tool_mapping에 넣으면 됨.

---

## A. Finance / Synapse 도구 (10개)

| # | tool_name | description | parameters |
|---|-----------|-------------|------------|
| 1 | get_case | 케이스 상세 정보를 조회합니다. Synapse 백엔드 Tool API를 통해 중복송장 의심 케이스 등의 상세를 가져옵니다. | caseId (string, 필수) |
| 2 | search_documents | 문서를 검색합니다. Synapse GET /documents (query: bukrs, gjahr, page, size 등). caseId만 있으면 get_case로 case 조회 후 bukrs/gjahr 추출하여 documents 호출. | filters (object, 선택) |
| 3 | get_document | 단일 문서를 조회합니다. | bukrs, belnr, gjahr (string, 필수) |
| 4 | get_lineage | 전표/문서의 라인리지(Lineage)를 조회합니다. caseId 우선, 없으면 belnr+gjahr(+bukrs) 사용. | caseId, belnr, gjahr, bukrs (string, 선택) |
| 5 | get_entity | 엔티티 정보를 조회합니다. | entityId (string, 필수) |
| 6 | get_open_items | 미결 항목(Open Items)을 조회합니다. Synapse GET /open-items (query: type, overdueBucket, page, size). | filters (object, 선택) |
| 7 | web_search | 외부 지능형 웹 검색. 회계/세무 기준, 국세청 가이드라인, 업종별 지출 관행 등. 검색 결과 URL은 [설명](URL) 마크다운으로 인용. | query (string, 필수) |
| 8 | simulate_action | 액션 시뮬레이션. 실제 실행 없이 결과 미리 확인. X-Idempotency-Key로 중복 호출 방지. | caseId, actionType (필수), payload, idempotency_key (선택) |
| 9 | propose_action | 액션 제안. 위험도 높거나 Guardrail 시 HITL 승인 필요. 승인 후 execute_action으로 실행. | caseId, actionType (필수), payload (선택) |
| 10 | execute_action | 승인 완료된 액션 실행. HITL 승인 후 actionId로 호출. X-Idempotency-Key로 중복 실행 방지. | actionId (필수), idempotency_key (선택) |

---

## B. Git 도구 (5개) — DEV 에이전트 등

| # | tool_name | description | parameters |
|---|-----------|-------------|------------|
| 11 | git_diff | Git diff 조회. 로컬 Git 저장소 변경사항 확인. | repo_path (필수), branch (선택, default HEAD), file_path (선택) |
| 12 | git_log | Git 커밋 로그 조회. 최근 커밋 히스토리 확인. | repo_path (필수), limit (선택, default 10), branch (선택, default HEAD) |
| 13 | git_status | Git 상태 조회. 작업 디렉토리 변경사항 확인. | repo_path (string, 필수) |
| 14 | git_show_file | 특정 커밋의 파일 내용 조회. 과거 버전 파일 확인. | repo_path, file_path (필수), commit (선택, default HEAD) |
| 15 | git_branch_list | Git 브랜치 목록 조회. 저장소 모든 브랜치 확인. | repo_path (필수), remote (boolean, 선택, default false) |

---

## C. GitHub 도구 (4개) — DEV 에이전트 등

| # | tool_name | description | parameters |
|---|-----------|-------------|------------|
| 16 | github_get_pr | GitHub PR 정보 조회. 제목, 설명, 상태 확인. | owner, repo (필수), pr_number (integer, 필수) |
| 17 | github_list_prs | GitHub PR 목록 조회. | owner, repo (필수), state (선택, default open), limit (선택, default 10) |
| 18 | github_get_pr_diff | GitHub PR 변경된 파일 목록 조회. | owner, repo (필수), pr_number (integer, 필수) |
| 19 | github_get_file | GitHub 저장소 파일 내용 조회. | owner, repo, path (필수), ref (선택, default main) |

---

## 요약

- **Finance/Synapse**: get_case, search_documents, get_document, get_entity, get_open_items, get_lineage, web_search, simulate_action, propose_action, execute_action
- **Git**: git_diff, git_log, git_status, git_show_file, git_branch_list
- **GitHub**: github_get_pr, github_list_prs, github_get_pr_diff, github_get_file

**agent_tool_inventory**: 위 19개 전부 등록. **agent_tool_mapping**: 에이전트별로 위 표 참고해 필요한 것만 매핑.  
Aura 코드: tools/synapse_finance_tool.py, tools/external_search_tool.py, tools/integrations/git_tool.py, tools/integrations/github_tool.py
