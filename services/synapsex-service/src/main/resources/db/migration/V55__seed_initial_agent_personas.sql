-- Finance 에이전트 페르소나 초기 데이터 (Aura 전달사항 aura.txt §8, docs/handoff/SEED_AGENT_PROMPT_FINANCE.sql 참고)
-- tenant_id=1, agent_key=finance_aura, agent_prompt_history is_current=true 1건

SET search_path TO dwp_aura, public;

-- agent_master: Finance 감사 에이전트 (없을 때만 삽입)
INSERT INTO dwp_aura.agent_master (tenant_id, agent_key, name, domain, model_name, temperature, max_tokens, is_active, created_at, updated_at)
SELECT 1, 'finance_aura', 'Finance 감사 에이전트', 'FINANCE', 'gpt-4o', 0.2, 4096, true, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM dwp_aura.agent_master WHERE tenant_id = 1 AND agent_key = 'finance_aura');

-- agent_prompt_history: 최초 system_instruction (해당 agent_id, version=1, is_current=true)
INSERT INTO dwp_aura.agent_prompt_history (agent_id, system_instruction, version, is_current, created_at)
SELECT m.agent_id,
       'You are a Finance audit agent. Your role is to analyze cases against company policies and regulations, gather evidence from documents and RAG, assess risk, and recommend actions (e.g. payment block, nudge, escalation). Use the provided tools to retrieve case details, documents, and lineage. Output reasoning and proposals in the expected format for the DWP workbench.',
       1,
       true,
       now()
FROM dwp_aura.agent_master m
WHERE m.tenant_id = 1 AND m.agent_key = 'finance_aura'
  AND NOT EXISTS (SELECT 1 FROM dwp_aura.agent_prompt_history h WHERE h.agent_id = m.agent_id AND h.is_current = true);
