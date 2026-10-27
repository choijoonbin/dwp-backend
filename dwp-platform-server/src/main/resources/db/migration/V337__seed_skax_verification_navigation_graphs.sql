-- The SKAX verification catalog contains usable sites, floors, and resources,
-- but an empty navigation topology makes the member wayfinding page look
-- unfinished. Seed a small published graph only for the bundled verification
-- tenant and only where no published graph already exists. Real tenant-authored
-- graphs always win.
DROP TABLE IF EXISTS tmp_v337_navigation_sites;

CREATE TEMP TABLE tmp_v337_navigation_sites AS
SELECT site.tenant_id,
       site.site_id,
       site.name_ko AS site_name_ko,
       site.name_en AS site_name_en,
       selected.floor_id,
       selected.resource_id,
       selected.resource_type,
       selected.resource_name_ko,
       selected.resource_name_en,
       md5('dwp:v337:graph:' || site.tenant_id || ':' || site.site_id)::uuid
           AS graph_revision_id,
       md5('dwp:v337:node:entry:' || site.tenant_id || ':' || site.site_id)::uuid
           AS entry_node_id,
       md5('dwp:v337:node:destination:' || site.tenant_id || ':' || site.site_id)::uuid
           AS destination_node_id,
       md5('dwp:v337:edge:' || site.tenant_id || ':' || site.site_id)::uuid
           AS edge_id,
       md5('dwp:v337:poi:entry:' || site.tenant_id || ':' || site.site_id)::uuid
           AS entry_poi_id,
       md5('dwp:v337:poi:destination:' || site.tenant_id || ':' || site.site_id)::uuid
           AS destination_poi_id
  FROM sys_service_tenants tenant
  JOIN wp_sites site
    ON site.tenant_id = tenant.tenant_id
   AND site.lifecycle_state = 'ACTIVE'
  JOIN LATERAL (
       SELECT floor.floor_id,
              resource.resource_id,
              resource.resource_type,
              resource.name_ko AS resource_name_ko,
              resource.name_en AS resource_name_en
         FROM wp_floors floor
         JOIN wp_resources resource
           ON resource.tenant_id = floor.tenant_id
          AND resource.floor_id = floor.floor_id
          AND resource.lifecycle_state <> 'RETIRED'
        WHERE floor.tenant_id = site.tenant_id
          AND floor.site_id = site.site_id
          AND floor.lifecycle_state <> 'CLOSED'
        ORDER BY CASE WHEN resource.resource_type = 'ROOM' THEN 0 ELSE 1 END,
                 floor.floor_number,
                 resource.resource_code
        LIMIT 1
  ) selected ON TRUE
 WHERE tenant.tenant_key = 'default'
   AND EXISTS (
       SELECT 1
         FROM cal_identity_links identity_link
        WHERE identity_link.tenant_id = tenant.tenant_id
          AND identity_link.user_id = 900018
          AND identity_link.person_public_id =
              '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid
   )
   AND NOT EXISTS (
       SELECT 1
         FROM wp_navigation_graph_revisions graph
        WHERE graph.tenant_id = site.tenant_id
          AND graph.site_id = site.site_id
          AND graph.lifecycle_state = 'PUBLISHED'
   );

INSERT INTO wp_navigation_graph_revisions (
    graph_revision_id, tenant_id, site_id, revision_number, lifecycle_state,
    content_hash, change_summary, version, submitted_at, submitted_by,
    published_at, published_by, created_at, created_by, updated_at, updated_by)
SELECT graph_revision_id, tenant_id, site_id, 1, 'PUBLISHED',
       md5('dwp:v337:content:a:' || tenant_id || ':' || site_id)
           || md5('dwp:v337:content:b:' || tenant_id || ':' || site_id),
       'Bundled SKAX verification route', 1,
       CURRENT_TIMESTAMP, 900018, CURRENT_TIMESTAMP, 900018,
       CURRENT_TIMESTAMP, 900018, CURRENT_TIMESTAMP, 900018
  FROM tmp_v337_navigation_sites
ON CONFLICT DO NOTHING;

INSERT INTO wp_navigation_nodes (
    node_id, tenant_id, graph_revision_id, floor_id, node_code, node_kind,
    position_x, position_y, accessible, restricted_zone_id)
SELECT seed.entry_node_id, seed.tenant_id, seed.graph_revision_id, seed.floor_id,
       'MAIN_ENTRY', 'ENTRY', 10, 50, TRUE, NULL::uuid
  FROM tmp_v337_navigation_sites seed
  JOIN wp_navigation_graph_revisions graph
    ON graph.tenant_id = seed.tenant_id
   AND graph.graph_revision_id = seed.graph_revision_id
UNION ALL
SELECT seed.destination_node_id, seed.tenant_id, seed.graph_revision_id, seed.floor_id,
       'DEFAULT_DESTINATION', 'POI', 75, 50, TRUE, NULL::uuid
  FROM tmp_v337_navigation_sites seed
  JOIN wp_navigation_graph_revisions graph
    ON graph.tenant_id = seed.tenant_id
   AND graph.graph_revision_id = seed.graph_revision_id
ON CONFLICT DO NOTHING;

INSERT INTO wp_navigation_edges (
    edge_id, tenant_id, graph_revision_id, from_node_id, to_node_id,
    travel_seconds, travel_mode, bidirectional, accessible, required_permission)
SELECT seed.edge_id, seed.tenant_id, seed.graph_revision_id,
       seed.entry_node_id, seed.destination_node_id,
       45, 'WALK', TRUE, TRUE, NULL
  FROM tmp_v337_navigation_sites seed
  JOIN wp_navigation_graph_revisions graph
    ON graph.tenant_id = seed.tenant_id
   AND graph.graph_revision_id = seed.graph_revision_id
ON CONFLICT DO NOTHING;

INSERT INTO wp_navigation_pois (
    poi_id, tenant_id, graph_revision_id, node_id, site_id, floor_id,
    resource_id, category, name_ko, name_en,
    direction_hint_ko, direction_hint_en, active)
SELECT seed.entry_poi_id, seed.tenant_id, seed.graph_revision_id,
       seed.entry_node_id, seed.site_id, seed.floor_id,
       NULL::uuid, 'ENTRY', seed.site_name_ko || ' 주 출입구', seed.site_name_en || ' main entrance',
       '주 출입구 안내 표지에서 출발하세요.',
       'Start at the main entrance wayfinding sign.', TRUE
  FROM tmp_v337_navigation_sites seed
  JOIN wp_navigation_graph_revisions graph
    ON graph.tenant_id = seed.tenant_id
   AND graph.graph_revision_id = seed.graph_revision_id
UNION ALL
SELECT seed.destination_poi_id, seed.tenant_id, seed.graph_revision_id,
       seed.destination_node_id, seed.site_id, seed.floor_id,
       seed.resource_id,
       CASE WHEN seed.resource_type = 'ROOM' THEN 'ROOM' ELSE 'OTHER' END,
       seed.resource_name_ko, seed.resource_name_en,
       '표시된 실내 통로를 따라 이동하세요.',
       'Follow the marked indoor corridor.', TRUE
  FROM tmp_v337_navigation_sites seed
  JOIN wp_navigation_graph_revisions graph
    ON graph.tenant_id = seed.tenant_id
   AND graph.graph_revision_id = seed.graph_revision_id
ON CONFLICT DO NOTHING;

DROP TABLE tmp_v337_navigation_sites;
