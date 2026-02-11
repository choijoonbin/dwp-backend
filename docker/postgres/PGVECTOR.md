# pgvector 사용 (PostgreSQL 15 호환)

## 이미지

- `docker-compose.yml` 에서 `postgres:15-alpine` → `pgvector/pgvector:pg15` 로 변경됨.
- 동일 메이저(15)이므로 기존 볼륨(`postgres_data`)의 데이터는 그대로 유지됩니다.

## 컨테이너 교체 (데이터 보존)

```bash
# 프로젝트 루트에서
docker-compose up -d postgres
```

- 기존 컨테이너가 새 이미지로 교체되며, **볼륨은 삭제되지 않습니다.**

## 백업 (권장)

중요 데이터가 있다면 작업 전 백업 권장:

```bash
docker exec dwp-postgres pg_dumpall -U dwp_user > backup_$(date +%Y%m%d_%H%M).sql
```

## pgvector 확장 활성화

이미지 교체 후, 벡터 기능을 쓸 **데이터베이스마다** 확장을 한 번씩 활성화합니다.

```bash
# RAG/벡터 사용 DB (예: dwp_aura) 에서 활성화
docker exec -it dwp-postgres psql -U dwp_user -d dwp_aura -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 필요 시 다른 DB에도 (예: postgres)
docker exec -it dwp-postgres psql -U dwp_user -d postgres -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

- `CREATE EXTENSION` 은 기존 테이블을 변경·삭제하지 않습니다.
- auth, main, mail, synapsex 등 기존 스키마/테이블에는 영향 없습니다.
