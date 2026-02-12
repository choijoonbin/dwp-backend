-- Aura 최종본: 도구 19개 전체 반영 — Git 5개 + GitHub 4개 추가, dev_aura 매핑
-- 기준: docs/handoff/TOOL_INVENTORY_FOR_BACKEND.md (에이전트별 도구 매핑 표)

SET search_path TO dwp_aura, public;

-- ----------------------------------------------------------------------
-- 1) agent_tool_inventory: Git 5개 + GitHub 4개 (Finance 10개는 V56/V58에서 이미 등록)
-- ----------------------------------------------------------------------
INSERT INTO dwp_aura.agent_tool_inventory (tool_name, description, schema_json, updated_at)
VALUES
  ('git_diff', 'Git diff를 조회합니다. 로컬 Git 저장소의 변경사항을 확인할 때 사용합니다.', '{"type":"object","properties":{"repo_path":{"type":"string","description":"저장소 경로"},"branch":{"type":"string","description":"브랜치 (default HEAD)"},"file_path":{"type":"string","description":"파일 경로"}},"required":["repo_path"]}'::jsonb, now()),
  ('git_log', 'Git 커밋 로그를 조회합니다. 최근 커밋 히스토리를 확인할 때 사용합니다.', '{"type":"object","properties":{"repo_path":{"type":"string","description":"저장소 경로"},"limit":{"type":"integer","description":"조회 개수 (default 10)"},"branch":{"type":"string","description":"브랜치 (default HEAD)"}},"required":["repo_path"]}'::jsonb, now()),
  ('git_status', 'Git 상태를 조회합니다. 현재 작업 디렉토리의 변경사항을 확인할 때 사용합니다.', '{"type":"object","properties":{"repo_path":{"type":"string","description":"저장소 경로"}},"required":["repo_path"]}'::jsonb, now()),
  ('git_show_file', '특정 커밋의 파일 내용을 조회합니다. 과거 버전의 파일을 확인할 때 사용합니다.', '{"type":"object","properties":{"repo_path":{"type":"string","description":"저장소 경로"},"file_path":{"type":"string","description":"파일 경로"},"commit":{"type":"string","description":"커밋 (default HEAD)"}},"required":["repo_path","file_path"]}'::jsonb, now()),
  ('git_branch_list', 'Git 브랜치 목록을 조회합니다. 저장소의 모든 브랜치를 확인할 때 사용합니다.', '{"type":"object","properties":{"repo_path":{"type":"string","description":"저장소 경로"},"remote":{"type":"boolean","description":"원격 포함 여부 (default false)"}},"required":["repo_path"]}'::jsonb, now()),
  ('github_get_pr', 'GitHub Pull Request 정보를 조회합니다. PR의 제목, 설명, 상태 등을 확인할 때 사용합니다.', '{"type":"object","properties":{"owner":{"type":"string","description":"소유자"},"repo":{"type":"string","description":"저장소명"},"pr_number":{"type":"integer","description":"PR 번호"}},"required":["owner","repo","pr_number"]}'::jsonb, now()),
  ('github_list_prs', 'GitHub Pull Request 목록을 조회합니다. 저장소의 PR 목록을 확인할 때 사용합니다.', '{"type":"object","properties":{"owner":{"type":"string","description":"소유자"},"repo":{"type":"string","description":"저장소명"},"state":{"type":"string","description":"상태 (default open)"},"limit":{"type":"integer","description":"조회 개수 (default 10)"}},"required":["owner","repo"]}'::jsonb, now()),
  ('github_get_pr_diff', 'GitHub Pull Request의 변경된 파일 목록을 조회합니다. PR에서 어떤 파일이 변경되었는지 확인할 때 사용합니다.', '{"type":"object","properties":{"owner":{"type":"string","description":"소유자"},"repo":{"type":"string","description":"저장소명"},"pr_number":{"type":"integer","description":"PR 번호"}},"required":["owner","repo","pr_number"]}'::jsonb, now()),
  ('github_get_file', 'GitHub 저장소의 파일 내용을 조회합니다. 특정 파일의 코드를 확인할 때 사용합니다.', '{"type":"object","properties":{"owner":{"type":"string","description":"소유자"},"repo":{"type":"string","description":"저장소명"},"path":{"type":"string","description":"파일 경로"},"ref":{"type":"string","description":"브랜치/ref (default main)"}},"required":["owner","repo","path"]}'::jsonb, now())
ON CONFLICT (tool_name) DO UPDATE SET
  description = EXCLUDED.description,
  schema_json = EXCLUDED.schema_json,
  updated_at = now();

-- ----------------------------------------------------------------------
-- 2) agent_tool_mapping: dev_aura ↔ Git 5개 + GitHub 4개 (9개)
-- ----------------------------------------------------------------------
INSERT INTO dwp_aura.agent_tool_mapping (agent_id, tool_id, created_at)
SELECT m.agent_id, i.tool_id, now()
FROM dwp_aura.agent_master m
JOIN dwp_aura.agent_tool_inventory i ON i.tool_name IN (
  'git_diff', 'git_log', 'git_status', 'git_show_file', 'git_branch_list',
  'github_get_pr', 'github_list_prs', 'github_get_pr_diff', 'github_get_file'
)
WHERE m.tenant_id = 1 AND m.agent_key = 'dev_aura'
  AND NOT EXISTS (
    SELECT 1 FROM dwp_aura.agent_tool_mapping mt
    WHERE mt.agent_id = m.agent_id AND mt.tool_id = i.tool_id
  );
