# DataGrip에서 SynapseX 서비스 테이블 보기

SynapseX 서비스 테이블은 **public** 스키마가 아니라 **dwp_aura** 스키마에 있습니다.

## 트리에서 보는 위치

```
[연결] (예: localhost:5432)
 └── dwp_aura          ← 데이터베이스
      └── Schemas
           └── dwp_aura    ← 여기 펼치기
                └── Tables
                     ├── agent_action
                     ├── agent_case
                     ├── bp_party
                     ├── config_profile
                     ├── fi_doc_header
                     ├── flyway_schema_history
                     └── ... (총 20개)
```

- **Database**: `dwp_aura`
- **Schema**: `dwp_aura` (public 아님)

## SQL로 확인

```sql
-- 스키마 dwp_aura의 테이블 목록
SET search_path TO dwp_aura;
\dt

-- 또는
SELECT tablename FROM pg_tables WHERE schemaname = 'dwp_aura' ORDER BY tablename;
```

## 연결이 Docker Postgres인 경우

- Host: localhost (또는 127.0.0.1)
- Port: 5432
- Database: dwp_aura
- User: dwp_user

연결 후 **Database** 드롭다운에서 **dwp_aura** 선택 → **Schemas** → **dwp_aura** 선택 후 테이블 목록 확인.
