# ADR: SynapseX Native Query 예외 승인

## 상태
승인됨 (2026-02)

## 배경
.cursorrules Persistence Rule에 따라 기본적으로 JPA + QueryDSL만 사용하며 Native Query는 금지된다.
다만, 다음 사유로 **제한적 예외**를 인정한다.

## 예외 대상

### 1. TenantScopeCommandService / TenantScopeCatalogService
- **용도**: FI 데이터(fi_doc_header, fi_open_item) 기반 **시드용** doc count 집계
- **쿼리**: `SELECT bukrs/waers, COUNT(*) ... GROUP BY ... ORDER BY cnt DESC`
- **사유**: 
  - fi_doc_header, fi_open_item은 JPA Entity가 없음 (Canonical 모델, 마이그레이션 V3)
  - 시드 시 "실제 사용 빈도" 기반 정렬이 필요
  - 조회 전용이며, CUD 없음
- **대안 검토**: 
  - QueryDSL로 구현 시 해당 테이블용 Q클래스 생성 필요
  - 시드 로직은 부트스트랩 단계에서만 호출되며 빈도 낮음
- **성능**: LIMIT 적용, 단순 집계

### 2. FiDocumentScopeRepository
- **용도**: 전표/미결제/케이스/조치 **스코프 필터 조회**
- **쿼리**: `SELECT ... FROM fi_doc_header ... WHERE tenant_id = :tid AND bukrs IN (:bukrs)`
- **사유**:
  - fi_doc_header, fi_open_item, agent_case, agent_action은 JPA Entity 미구성
  - 스코프 필터(IN 절) 적용이 필수
- **대안**: 추후 Entity + QueryDSL 전환 시 제거

## 결론
- 위 3건에 한해 Native Query 사용 허용
- 신규 도메인 테이블은 Entity + QueryDSL 우선 적용
