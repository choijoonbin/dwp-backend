# V44 Menu Tree API 응답 예시

## 적용 내용 (V44 마이그레이션)

1. **Workbench Elevation**: `menu.autonomous-operations.workbench` → `parent_menu_key = NULL`, `menu_name_ko = '통합 워크벤치'`, `sort_order = 1`, `depth = 1`
2. **Obsolete Group**: `menu.autonomous-operations` (자율 운영 센터) → `is_visible = 'N'`
3. **Path Alignment**: `menu.command-center` → `menu_path = '/synapse/workbench'`, `is_visible = 'N'`
4. **Group Sync**: SynapseX 관련 메뉴 전부 `menu_group = 'SynapseX'`

---

## GET /api/auth/menus/tree 응답 JSON 예시

*`is_visible = 'N'` 인 메뉴를 트리에서 제외하는 경우, 통합 워크벤치가 최상위 첫 번째로 오고, 자율 운영 센터·통합 관제 센터는 목록에 나타나지 않습니다.*

```json
{
  "success": true,
  "data": {
    "menus": [
      {
        "menuKey": "menu.autonomous-operations.workbench",
        "menuName": "통합 워크벤치",
        "path": "/synapse/workbench",
        "icon": "solar:widget-bold",
        "group": "SynapseX",
        "depth": 1,
        "sortOrder": 1,
        "children": [],
        "resourceKind": "PAGE",
        "trackingEnabled": true
      },
      {
        "menuKey": "menu.master-data-history",
        "menuName": "원천 데이터·이력 허브",
        "path": "menu.master-data-history",
        "icon": "solar:database-bold",
        "group": "SynapseX",
        "depth": 1,
        "sortOrder": 30,
        "children": [
          {
            "menuKey": "menu.master-data-history.documents",
            "menuName": "전표 조회",
            "path": "/synapse/documents",
            "icon": "solar:document-bold",
            "group": "SynapseX",
            "depth": 2,
            "sortOrder": 21,
            "children": [],
            "resourceKind": "PAGE",
            "trackingEnabled": true
          },
          {
            "menuKey": "menu.master-data-history.open-items",
            "menuName": "미결제 항목",
            "path": "/synapse/open-items",
            "icon": "solar:wallet-bold",
            "group": "SynapseX",
            "depth": 2,
            "sortOrder": 22,
            "children": [],
            "resourceKind": "PAGE",
            "trackingEnabled": true
          }
        ],
        "resourceKind": "MENU_GROUP",
        "trackingEnabled": true
      },
      {
        "menuKey": "menu.knowledge-policy",
        "menuName": "지식·정책 허브",
        "path": "menu.knowledge-policy",
        "icon": "solar:book-bold",
        "group": "SynapseX",
        "depth": 1,
        "sortOrder": 40,
        "children": [
          {
            "menuKey": "menu.knowledge-policy.rag",
            "menuName": "규정·문서 라이브러리",
            "path": "/synapse/rag",
            "icon": "solar:book-2-bold",
            "group": "SynapseX",
            "depth": 2,
            "sortOrder": 31,
            "children": [],
            "resourceKind": "PAGE",
            "trackingEnabled": true
          }
        ],
        "resourceKind": "MENU_GROUP",
        "trackingEnabled": true
      }
    ],
    "groups": [
      {
        "groupCode": "SynapseX",
        "groupName": "SynapseX",
        "menus": [
          {
            "menuKey": "menu.autonomous-operations.workbench",
            "menuName": "통합 워크벤치",
            "path": "/synapse/workbench",
            "icon": "solar:widget-bold",
            "group": "SynapseX",
            "depth": 1,
            "sortOrder": 1,
            "children": []
          },
          {
            "menuKey": "menu.master-data-history",
            "menuName": "원천 데이터·이력 허브",
            "path": "menu.master-data-history",
            "icon": "solar:database-bold",
            "group": "SynapseX",
            "depth": 1,
            "sortOrder": 30,
            "children": []
          },
          {
            "menuKey": "menu.knowledge-policy",
            "menuName": "지식·정책 허브",
            "path": "menu.knowledge-policy",
            "icon": "solar:book-bold",
            "group": "SynapseX",
            "depth": 1,
            "sortOrder": 40,
            "children": []
          }
        ]
      }
    ]
  }
}
```

- **통합 워크벤치**가 `menus[0]` 이며 `path: "/synapse/workbench"`, `group: "SynapseX"`, `sortOrder: 1` 로 단일 진입점으로 노출됩니다.
- 모든 Synapse 관련 메뉴는 `group: "SynapseX"` 로 일관되게 반환됩니다.
- 트리에서 `is_visible = 'N'` 인 항목을 제외하려면, 메뉴 트리 조회 시 `is_visible = 'Y'` 조건을 적용하는 백엔드/프론트 필터가 필요합니다.
