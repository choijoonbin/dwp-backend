# Finance 에이전트 페르소나 Seed 참고

- **Aura 전달**: aura.txt §8 — `system_instruction` 있으면 Aura가 최우선 사용, Seed는 `docs/handoff/SEED_AGENT_PROMPT_FINANCE.sql` 참고.
- **백엔드 반영**: Flyway `V55__seed_initial_agent_personas.sql`에서 `agent_master`(finance_aura) + `agent_prompt_history`(version=1, is_current=true) 초기 데이터 적재.
- Aura 팀에서 추출한 **전문 SQL**을 수령한 경우, `system_instruction` 본문만 해당 마이그레이션 또는 별도 패치로 교체하면 됨.
