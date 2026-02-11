# Global Menu Structure Realignment (V47) — 결과 보고

## 1. 적용 SQL (V47)

**파일:** `dwp-auth-server/src/main/resources/db/migration/V47__global_menu_structure_realignment_level4.sql`

| 단계 | 내용 |
|------|------|
| 1 | **통합 워크벤치**: `menu.autonomous-operations.workbench` → `menu.workbench` (sys_menus + com_resources), Depth 1, sort 10, icon `solar:clapperboard-edit-bold` |
| 2 | **command-center**: `is_visible='N'` (비노출) |
| 3 | **지식·정책 허브**: `parent_menu_key=NULL`, depth=1, sort=20, icon `solar:library-bold`, `is_visible='Y'` |
| 4 | **거버넌스·설정**: depth=1, sort=30, icon `solar:settings-minimalistic-bold`, `is_visible='Y'` |
| 5 | **원천 데이터·이력**: `parent_menu_key=menu.governance-config`, depth=2, sort=31, icon `solar:database-bold`; 하위 메뉴 depth=3 |
| 6 | **정합성 대사 리포트**: `menu.reconciliation-audit` → 거버넌스 하위, depth=2, sort=32, 명칭 "정합성 대사 리포트", icon `solar:clipboard-list-bold`; 하위 depth=3 |
| 7 | **관리 서비스**: `menu.admin` depth=1, sort=900, menu_name_ko='관리 서비스', icon `solar:shield-user-bold`, `is_visible='Y'` |
| 8 | **자율 운영 센터**: `menu.autonomous-operations` `is_visible='N'` 유지 |

## 2. 최종 메뉴 뎁스/정렬 (Target Map 반영)

| Depth | 메뉴명 | menu_key | parent_menu_key | sort_order | icon |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | 통합 워크벤치 | `menu.workbench` | NULL | 10 | solar:clapperboard-edit-bold |
| **1** | 지식·정책 허브 | `menu.knowledge-policy` | NULL | 20 | solar:library-bold |
| **1** | 거버넌스·설정 | `menu.governance-config` | NULL | 30 | solar:settings-minimalistic-bold |
| 2 | 원천 데이터·이력 | `menu.master-data-history` | menu.governance-config | 31 | solar:database-bold |
| 2 | 정합성 대사 리포트 | `menu.reconciliation-audit` | menu.governance-config | 32 | solar:clipboard-list-bold |
| **1** | 관리 서비스 | `menu.admin` | NULL | 900 | solar:shield-user-bold |

## 3. API 확인

- **메뉴 트리**: `GET /api/auth/menus/tree` (또는 로그인 응답 내 `menus`)
- **권한**: 사용자에게 `menu.workbench`, `menu.knowledge-policy`, `menu.governance-config`, `menu.admin` 등에 VIEW 권한이 있으면 해당 루트 및 하위만 반환
- **1뎁스 빈 children**: `is_visible='Y'` 로 유지하므로, 자식이 없어도 루트 노드는 반환되며 `children: []` 로 내려감

## 4. 최종 메뉴 응답 JSON 샘플 (트리 형태)

```json
{
  "status": "SUCCESS",
  "data": {
    "menus": [
      {
        "id": 123,
        "menuKey": "menu.workbench",
        "menuName": "통합 워크벤치",
        "path": "/synapse/workbench",
        "icon": "solar:clapperboard-edit-bold",
        "group": "MANAGEMENT",
        "depth": 1,
        "sortOrder": 10,
        "children": []
      },
      {
        "id": 124,
        "menuKey": "menu.knowledge-policy",
        "menuName": "지식·정책 허브",
        "path": null,
        "icon": "solar:library-bold",
        "group": "MANAGEMENT",
        "depth": 1,
        "sortOrder": 20,
        "children": [
          {
            "id": 125,
            "menuKey": "menu.knowledge-policy.rag",
            "menuName": "규정·문서 라이브러리",
            "path": "/synapse/rag",
            "icon": "solar:book-2-bold",
            "depth": 2,
            "sortOrder": 231,
            "children": []
          }
        ]
      },
      {
        "id": 126,
        "menuKey": "menu.governance-config",
        "menuName": "거버넌스·설정",
        "path": null,
        "icon": "solar:settings-minimalistic-bold",
        "group": "MANAGEMENT",
        "depth": 1,
        "sortOrder": 30,
        "children": [
          {
            "id": 127,
            "menuKey": "menu.master-data-history",
            "menuName": "원천 데이터·이력",
            "path": null,
            "icon": "solar:database-bold",
            "depth": 2,
            "sortOrder": 31,
            "children": [
              {
                "id": 128,
                "menuKey": "menu.master-data-history.documents",
                "menuName": "전표 조회",
                "path": "/synapse/documents",
                "depth": 3,
                "children": []
              }
            ]
          },
          {
            "id": 129,
            "menuKey": "menu.reconciliation-audit",
            "menuName": "정합성 대사 리포트",
            "path": null,
            "icon": "solar:clipboard-list-bold",
            "depth": 2,
            "sortOrder": 32,
            "children": []
          }
        ]
      },
      {
        "id": 130,
        "menuKey": "menu.admin",
        "menuName": "관리 서비스",
        "path": "/admin",
        "icon": "solar:shield-user-bold",
        "group": "MANAGEMENT",
        "depth": 1,
        "sortOrder": 900,
        "children": []
      }
    ],
    "groups": []
  }
}
```

실제 `id`/`sortOrder` 및 하위 개수는 DB·권한에 따라 달라질 수 있으며, 위는 구조 예시입니다.

## 5. Data Integrity

- **com_resources**: `menu.autonomous-operations.workbench` → `menu.workbench` 로 `key` 업데이트, `parent_resource_id=NULL`
- **com_role_permissions**: `resource_id` 기준이라 변경 없음 (동일 리소스)
- **sys_menus**: `menu_key` 변경 시 기존 행 1건만 업데이트하므로 unique(tenant_id, menu_key) 유지
