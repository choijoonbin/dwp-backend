# sap_dummy – Aura CSV 더미/시드 데이터

Aura 서비스(dwp_aura) 개발·테스트용 CSV 파일을 모아두는 폴더입니다.

## CSV 목록

| 파일 | 대상 테이블 | 실행 스크립트 |
|------|-------------|----------------|
| bp_party.csv | dwp_aura.bp_party | run-aura-load-csv.sh |
| fi_doc_header.csv | dwp_aura.fi_doc_header | run-aura-load-csv.sh |
| fi_doc_item.csv | dwp_aura.fi_doc_item | run-aura-load-csv.sh |
| fi_open_item.csv | dwp_aura.fi_open_item | run-aura-load-csv.sh |
| sap_change_log.csv | dwp_aura.sap_change_log | run-aura-load-csv.sh |
| agent_case_seed.csv | dwp_aura.agent_case (변환 적재) | run-aura-seed.sh |

## 실행 방법 (프로젝트 루트에서)

```bash
# 1) CSV 5종 → bp_party, fi_doc_*, sap_change_log 적재
./scripts/run-aura-load-csv.sh

# 기존 데이터 삭제 후 다시 적재 (dev 전용)
./scripts/run-aura-load-csv.sh --truncate

# 2) config/policy 시드 + agent_case_seed.csv → agent_case 적재
./scripts/run-aura-seed.sh
```

기본 경로는 이 `sap_dummy` 폴더입니다. 다른 경로를 쓰려면 인자로 넘기면 됩니다.
