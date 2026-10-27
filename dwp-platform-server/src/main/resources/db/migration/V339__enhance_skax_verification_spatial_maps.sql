-- Promote only the immutable V157/V337 bundled verification fixtures to a
-- spatially useful floor plan and navigation graph.  Every selector includes
-- the deterministic fixture id and its original provenance so customer or
-- administrator-authored plans/graphs are never adopted or overwritten.

DROP TABLE IF EXISTS pg_temp.tmp_v339_floor_plans;
CREATE TEMP TABLE tmp_v339_floor_plans AS
SELECT floor.tenant_id,
       floor.floor_id,
       floor.site_id,
       floor.published_plan_revision_id AS baseline_revision_id,
       md5('dwp:v339:floor-plan:' || floor.tenant_id || ':' || floor.floor_id)::uuid
           AS spatial_revision_id,
       CASE site.site_code
           WHEN 'PANGYO' THEN '/assets/workplace/skax-pangyo-floor-v1.svg'
           WHEN 'SEOUL' THEN '/assets/workplace/skax-seoul-floor-v1.svg'
           WHEN 'SHARED-GANGNAM' THEN '/assets/workplace/skax-gangnam-floor-v1.svg'
       END AS asset_path
  FROM sys_service_tenants tenant
  JOIN wp_sites site
    ON site.tenant_id = tenant.tenant_id
   AND site.site_code IN ('PANGYO', 'SEOUL', 'SHARED-GANGNAM')
   AND site.lifecycle_state = 'ACTIVE'
  JOIN wp_floors floor
    ON floor.tenant_id = site.tenant_id
   AND floor.site_id = site.site_id
   AND floor.lifecycle_state = 'ACTIVE'
  JOIN wp_floor_plan_revisions revision
    ON revision.tenant_id = floor.tenant_id
   AND revision.floor_id = floor.floor_id
   AND revision.floor_plan_revision_id = floor.published_plan_revision_id
   AND revision.floor_plan_revision_id =
       md5('workplace:floor-plan:' || floor.tenant_id || ':' || floor.floor_id || ':1')::uuid
   AND revision.revision_number = 1
   AND revision.lifecycle_state = 'PUBLISHED'
   AND revision.change_summary = 'V157 baseline migration'
   AND revision.content_hash =
       md5('workplace:floor-plan:' || floor.tenant_id || ':' || floor.floor_id || ':1')
       || md5('workplace:floor-plan:' || floor.tenant_id || ':' || floor.floor_id || ':1:content')
 WHERE tenant.tenant_key = 'default'
   AND floor.background_asset_path IS NULL
   AND floor.background_asset_key IS NULL
   AND revision.background_asset_path IS NULL
   AND revision.background_asset_key IS NULL
   AND EXISTS (
       SELECT 1
         FROM cal_identity_links identity_link
        WHERE identity_link.tenant_id = tenant.tenant_id
          AND identity_link.user_id = 900018
          AND identity_link.person_public_id =
              '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid
   )
   -- An open or historical second revision is authored work.  Leave it alone.
   AND NOT EXISTS (
       SELECT 1
         FROM wp_floor_plan_revisions other
        WHERE other.tenant_id = floor.tenant_id
          AND other.floor_id = floor.floor_id
          AND other.floor_plan_revision_id <> revision.floor_plan_revision_id
   );

INSERT INTO wp_floor_plan_revisions (
    floor_plan_revision_id, tenant_id, floor_id, revision_number,
    based_on_revision_id, lifecycle_state, plan_width, plan_height,
    background_asset_path, change_summary, content_hash, version,
    created_at, created_by, updated_at, updated_by)
SELECT spatial_revision_id, seed.tenant_id, seed.floor_id, 2,
       seed.baseline_revision_id, 'DRAFT', floor.plan_width, floor.plan_height,
       seed.asset_path, 'Bundled SKAX verification floor plan',
       md5('dwp:v339:floor-plan-content:a:' || seed.tenant_id || ':' || seed.floor_id)
       || md5('dwp:v339:floor-plan-content:b:' || seed.tenant_id || ':' || seed.floor_id),
       1, CURRENT_TIMESTAMP, 900018, CURRENT_TIMESTAMP, 900018
  FROM tmp_v339_floor_plans seed
  JOIN wp_floors floor
    ON floor.tenant_id = seed.tenant_id
   AND floor.floor_id = seed.floor_id
ON CONFLICT DO NOTHING;

INSERT INTO wp_floor_plan_revision_placements (
    placement_id, tenant_id, floor_id, floor_plan_revision_id, resource_id,
    resource_version, zone_id, section_id, position_x, position_y,
    width_percent, height_percent, rotation_degrees, placement_metadata,
    version, created_at, created_by, updated_at, updated_by)
SELECT md5('dwp:v339:placement:' || source.tenant_id || ':'
           || seed.spatial_revision_id || ':' || source.resource_id)::uuid,
       source.tenant_id, source.floor_id, seed.spatial_revision_id,
       source.resource_id, source.resource_version, source.zone_id, source.section_id,
       source.position_x, source.position_y, source.width_percent, source.height_percent,
       source.rotation_degrees, source.placement_metadata, 1,
       CURRENT_TIMESTAMP, 900018, CURRENT_TIMESTAMP, 900018
  FROM tmp_v339_floor_plans seed
  JOIN wp_floor_plan_revision_placements source
    ON source.tenant_id = seed.tenant_id
   AND source.floor_id = seed.floor_id
   AND source.floor_plan_revision_id = seed.baseline_revision_id
ON CONFLICT DO NOTHING;

UPDATE wp_floor_plan_revisions revision
   SET lifecycle_state = 'REVIEW',
       submitted_at = CURRENT_TIMESTAMP,
       submitted_by = 900018,
       version = 2,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 900018
  FROM tmp_v339_floor_plans seed
 WHERE revision.tenant_id = seed.tenant_id
   AND revision.floor_plan_revision_id = seed.spatial_revision_id
   AND revision.lifecycle_state = 'DRAFT'
   AND revision.version = 1;

UPDATE wp_floor_plan_revisions revision
   SET lifecycle_state = 'ARCHIVED',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 900018
  FROM tmp_v339_floor_plans seed
 WHERE revision.tenant_id = seed.tenant_id
   AND revision.floor_plan_revision_id = seed.baseline_revision_id
   AND revision.lifecycle_state = 'PUBLISHED';

UPDATE wp_floor_plan_revisions revision
   SET lifecycle_state = 'PUBLISHED',
       published_at = CURRENT_TIMESTAMP,
       published_by = 900018,
       version = 3,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 900018
  FROM tmp_v339_floor_plans seed
 WHERE revision.tenant_id = seed.tenant_id
   AND revision.floor_plan_revision_id = seed.spatial_revision_id
   AND revision.lifecycle_state = 'REVIEW'
   AND revision.version = 2;

UPDATE wp_floors floor
   SET published_plan_revision_id = seed.spatial_revision_id,
       background_asset_path = seed.asset_path,
       version = floor.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 900018
  FROM tmp_v339_floor_plans seed
 WHERE floor.tenant_id = seed.tenant_id
   AND floor.floor_id = seed.floor_id
   AND floor.published_plan_revision_id = seed.baseline_revision_id
   AND floor.background_asset_path IS NULL
   AND floor.background_asset_key IS NULL;

DROP TABLE pg_temp.tmp_v339_floor_plans;

DROP TABLE IF EXISTS pg_temp.tmp_v339_navigation_sites;
CREATE TEMP TABLE tmp_v339_navigation_sites AS
SELECT graph.tenant_id,
       graph.site_id,
       site.site_code,
       site.name_ko AS site_name_ko,
       site.name_en AS site_name_en,
       graph.graph_revision_id AS baseline_graph_id,
       entry.floor_id AS entry_floor_id,
       md5('dwp:v339:graph:' || graph.tenant_id || ':' || graph.site_id)::uuid
           AS spatial_graph_id
  FROM sys_service_tenants tenant
  JOIN wp_sites site
    ON site.tenant_id = tenant.tenant_id
   AND site.site_code IN ('PANGYO', 'SEOUL', 'SHARED-GANGNAM')
   AND site.lifecycle_state = 'ACTIVE'
  JOIN wp_navigation_graph_revisions graph
    ON graph.tenant_id = site.tenant_id
   AND graph.site_id = site.site_id
   AND graph.graph_revision_id =
       md5('dwp:v337:graph:' || site.tenant_id || ':' || site.site_id)::uuid
   AND graph.revision_number = 1
   AND graph.lifecycle_state = 'PUBLISHED'
   AND graph.change_summary = 'Bundled SKAX verification route'
   AND graph.content_hash =
       md5('dwp:v337:content:a:' || site.tenant_id || ':' || site.site_id)
       || md5('dwp:v337:content:b:' || site.tenant_id || ':' || site.site_id)
   AND graph.version = 1
   AND graph.submitted_by = 900018
   AND graph.published_by = 900018
  JOIN wp_navigation_nodes entry
    ON entry.tenant_id = graph.tenant_id
   AND entry.graph_revision_id = graph.graph_revision_id
   AND entry.node_id =
       md5('dwp:v337:node:entry:' || graph.tenant_id || ':' || graph.site_id)::uuid
   AND entry.node_code = 'MAIN_ENTRY'
   AND entry.node_kind = 'ENTRY'
   AND entry.position_x = 10
   AND entry.position_y = 50
  JOIN wp_navigation_nodes destination
    ON destination.tenant_id = graph.tenant_id
   AND destination.graph_revision_id = graph.graph_revision_id
   AND destination.node_id =
       md5('dwp:v337:node:destination:' || graph.tenant_id || ':' || graph.site_id)::uuid
   AND destination.node_code = 'DEFAULT_DESTINATION'
   AND destination.node_kind = 'POI'
   AND destination.position_x = 75
   AND destination.position_y = 50
  JOIN wp_navigation_edges edge
    ON edge.tenant_id = graph.tenant_id
   AND edge.graph_revision_id = graph.graph_revision_id
   AND edge.edge_id =
       md5('dwp:v337:edge:' || graph.tenant_id || ':' || graph.site_id)::uuid
   AND edge.from_node_id = entry.node_id
   AND edge.to_node_id = destination.node_id
   AND edge.travel_seconds = 45
   AND edge.travel_mode = 'WALK'
   AND edge.bidirectional
   AND edge.accessible
 WHERE tenant.tenant_key = 'default'
   AND EXISTS (
       SELECT 1
         FROM cal_identity_links identity_link
        WHERE identity_link.tenant_id = tenant.tenant_id
          AND identity_link.user_id = 900018
          AND identity_link.person_public_id =
              '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid
   )
   AND (SELECT COUNT(*) FROM wp_navigation_nodes node
         WHERE node.tenant_id = graph.tenant_id
           AND node.graph_revision_id = graph.graph_revision_id) = 2
   AND (SELECT COUNT(*) FROM wp_navigation_edges candidate
         WHERE candidate.tenant_id = graph.tenant_id
           AND candidate.graph_revision_id = graph.graph_revision_id) = 1
   AND (SELECT COUNT(*) FROM wp_navigation_pois poi
         WHERE poi.tenant_id = graph.tenant_id
           AND poi.graph_revision_id = graph.graph_revision_id) = 2
   AND NOT EXISTS (
       SELECT 1
         FROM wp_navigation_graph_revisions other
        WHERE other.tenant_id = graph.tenant_id
          AND other.site_id = graph.site_id
          AND other.graph_revision_id <> graph.graph_revision_id
   );

INSERT INTO wp_navigation_graph_revisions (
    graph_revision_id, tenant_id, site_id, revision_number, lifecycle_state,
    content_hash, change_summary, version, submitted_at, submitted_by,
    published_at, published_by, created_at, created_by, updated_at, updated_by)
SELECT spatial_graph_id, tenant_id, site_id, 2, 'DRAFT',
       md5('dwp:v339:content:a:' || tenant_id || ':' || site_id)
       || md5('dwp:v339:content:b:' || tenant_id || ':' || site_id),
       'Bundled SKAX verification spatial route', 1,
       NULL, NULL, NULL, NULL,
       CURRENT_TIMESTAMP, 900018, CURRENT_TIMESTAMP, 900018
  FROM tmp_v339_navigation_sites
ON CONFLICT DO NOTHING;

DROP TABLE IF EXISTS pg_temp.tmp_v339_navigation_floors;
CREATE TEMP TABLE tmp_v339_navigation_floors AS
SELECT seed.tenant_id, seed.site_id, seed.spatial_graph_id, floor.floor_id,
       floor.floor_number,
       row_number() OVER (
           PARTITION BY seed.tenant_id, seed.site_id ORDER BY floor.floor_number, floor.floor_id
       ) AS floor_order,
       md5('dwp:v339:node:west:' || seed.tenant_id || ':' || seed.site_id || ':'
           || floor.floor_id)::uuid AS west_node_id,
       md5('dwp:v339:node:center:' || seed.tenant_id || ':' || seed.site_id || ':'
           || floor.floor_id)::uuid AS center_node_id,
       md5('dwp:v339:node:east:' || seed.tenant_id || ':' || seed.site_id || ':'
           || floor.floor_id)::uuid AS east_node_id,
       md5('dwp:v339:node:elevator:' || seed.tenant_id || ':' || seed.site_id || ':'
           || floor.floor_id)::uuid AS elevator_node_id,
       md5('dwp:v339:node:stair:' || seed.tenant_id || ':' || seed.site_id || ':'
           || floor.floor_id)::uuid AS stair_node_id
  FROM tmp_v339_navigation_sites seed
  JOIN wp_floors floor
    ON floor.tenant_id = seed.tenant_id
   AND floor.site_id = seed.site_id
   AND floor.lifecycle_state = 'ACTIVE';

INSERT INTO wp_navigation_nodes (
    node_id, tenant_id, graph_revision_id, floor_id, node_code, node_kind,
    position_x, position_y, accessible, restricted_zone_id)
SELECT node_id, tenant_id, spatial_graph_id, floor_id, node_code, node_kind,
       position_x, position_y, accessible, NULL::uuid
  FROM (
      SELECT west_node_id AS node_id, tenant_id, spatial_graph_id, floor_id,
             'F' || floor_number || '_CORRIDOR_WEST' AS node_code,
             'JUNCTION' AS node_kind, 12::numeric AS position_x,
             58::numeric AS position_y, TRUE AS accessible
        FROM tmp_v339_navigation_floors
      UNION ALL
      SELECT center_node_id, tenant_id, spatial_graph_id, floor_id,
             'F' || floor_number || '_CORRIDOR_CENTER', 'JUNCTION', 50, 58, TRUE
        FROM tmp_v339_navigation_floors
      UNION ALL
      SELECT east_node_id, tenant_id, spatial_graph_id, floor_id,
             'F' || floor_number || '_CORRIDOR_EAST', 'JUNCTION', 88, 58, TRUE
        FROM tmp_v339_navigation_floors
      UNION ALL
      SELECT elevator_node_id, tenant_id, spatial_graph_id, floor_id,
             'F' || floor_number || '_ELEVATOR', 'ELEVATOR', 58, 78, TRUE
        FROM tmp_v339_navigation_floors
      UNION ALL
      SELECT stair_node_id, tenant_id, spatial_graph_id, floor_id,
             'F' || floor_number || '_STAIR', 'STAIR', 72, 78, FALSE
        FROM tmp_v339_navigation_floors
  ) nodes
ON CONFLICT DO NOTHING;

INSERT INTO wp_navigation_nodes (
    node_id, tenant_id, graph_revision_id, floor_id, node_code, node_kind,
    position_x, position_y, accessible, restricted_zone_id)
SELECT md5('dwp:v339:node:entry:' || seed.tenant_id || ':' || seed.site_id)::uuid,
       seed.tenant_id, seed.spatial_graph_id, seed.entry_floor_id,
       'MAIN_ENTRY', 'ENTRY', 92, 90, TRUE, NULL::uuid
  FROM tmp_v339_navigation_sites seed
ON CONFLICT DO NOTHING;

DROP TABLE IF EXISTS pg_temp.tmp_v339_navigation_resources;
CREATE TEMP TABLE tmp_v339_navigation_resources AS
SELECT seed.tenant_id, seed.site_id, seed.spatial_graph_id,
       resource.floor_id, resource.resource_id, resource.resource_code,
       resource.resource_type, resource.name_ko, resource.name_en,
       LEAST(100::numeric, resource.position_x + resource.width_percent / 2) AS position_x,
       LEAST(100::numeric, resource.position_y + resource.height_percent / 2) AS position_y,
       resource.accessible,
       md5('dwp:v339:node:resource:' || seed.tenant_id || ':'
           || resource.resource_id)::uuid AS node_id,
       md5('dwp:v339:poi:resource:' || seed.tenant_id || ':'
           || resource.resource_id)::uuid AS poi_id
  FROM tmp_v339_navigation_sites seed
  JOIN wp_floors floor
    ON floor.tenant_id = seed.tenant_id
   AND floor.site_id = seed.site_id
   AND floor.lifecycle_state = 'ACTIVE'
  JOIN wp_resources resource
    ON resource.tenant_id = floor.tenant_id
   AND resource.floor_id = floor.floor_id
   AND resource.lifecycle_state <> 'RETIRED';

INSERT INTO wp_navigation_nodes (
    node_id, tenant_id, graph_revision_id, floor_id, node_code, node_kind,
    position_x, position_y, accessible, restricted_zone_id)
SELECT node_id, tenant_id, spatial_graph_id, floor_id,
       'RESOURCE_' || replace(resource_id::text, '-', ''), 'POI',
       position_x, position_y, accessible, NULL::uuid
  FROM tmp_v339_navigation_resources
ON CONFLICT DO NOTHING;

-- Horizontal corridors and service-core connections for every active floor.
INSERT INTO wp_navigation_edges (
    edge_id, tenant_id, graph_revision_id, from_node_id, to_node_id,
    travel_seconds, travel_mode, bidirectional, accessible, required_permission)
SELECT edge_id, tenant_id, spatial_graph_id, from_node_id, to_node_id,
       travel_seconds, travel_mode, TRUE, accessible, NULL
  FROM (
      SELECT md5('dwp:v339:edge:west-center:' || tenant_id || ':' || floor_id)::uuid edge_id,
             tenant_id, spatial_graph_id, west_node_id from_node_id,
             center_node_id to_node_id, 30 travel_seconds, 'WALK' travel_mode,
             TRUE accessible
        FROM tmp_v339_navigation_floors
      UNION ALL
      SELECT md5('dwp:v339:edge:center-east:' || tenant_id || ':' || floor_id)::uuid,
             tenant_id, spatial_graph_id, center_node_id, east_node_id, 30, 'WALK', TRUE
        FROM tmp_v339_navigation_floors
      UNION ALL
      SELECT md5('dwp:v339:edge:center-elevator:' || tenant_id || ':' || floor_id)::uuid,
             tenant_id, spatial_graph_id, center_node_id, elevator_node_id, 16, 'WALK', TRUE
        FROM tmp_v339_navigation_floors
      UNION ALL
      SELECT md5('dwp:v339:edge:center-stair:' || tenant_id || ':' || floor_id)::uuid,
             tenant_id, spatial_graph_id, center_node_id, stair_node_id, 18, 'WALK', FALSE
        FROM tmp_v339_navigation_floors
  ) edges
ON CONFLICT DO NOTHING;

-- Join the main entrance to the east corridor on the entry floor.
INSERT INTO wp_navigation_edges (
    edge_id, tenant_id, graph_revision_id, from_node_id, to_node_id,
    travel_seconds, travel_mode, bidirectional, accessible, required_permission)
SELECT md5('dwp:v339:edge:entry:' || seed.tenant_id || ':' || seed.site_id)::uuid,
       seed.tenant_id, seed.spatial_graph_id,
       md5('dwp:v339:node:entry:' || seed.tenant_id || ':' || seed.site_id)::uuid,
       floor.east_node_id, 18, 'WALK', TRUE, TRUE, NULL
  FROM tmp_v339_navigation_sites seed
  JOIN tmp_v339_navigation_floors floor
    ON floor.tenant_id = seed.tenant_id
   AND floor.site_id = seed.site_id
   AND floor.floor_id = seed.entry_floor_id
ON CONFLICT DO NOTHING;

-- Connect each registered resource to the nearest corridor junction.
INSERT INTO wp_navigation_edges (
    edge_id, tenant_id, graph_revision_id, from_node_id, to_node_id,
    travel_seconds, travel_mode, bidirectional, accessible, required_permission)
SELECT md5('dwp:v339:edge:resource:' || resource.tenant_id || ':'
           || resource.resource_id)::uuid,
       resource.tenant_id, resource.spatial_graph_id, resource.node_id,
       CASE WHEN resource.position_x < 33 THEN floor.west_node_id
            WHEN resource.position_x < 66 THEN floor.center_node_id
            ELSE floor.east_node_id END,
       GREATEST(6, LEAST(120, ROUND(
           ABS(resource.position_y - 58)
           + ABS(resource.position_x - CASE WHEN resource.position_x < 33 THEN 12
                                          WHEN resource.position_x < 66 THEN 50
                                          ELSE 88 END)
       )::integer)),
       'WALK', TRUE, resource.accessible, NULL
  FROM tmp_v339_navigation_resources resource
  JOIN tmp_v339_navigation_floors floor
    ON floor.tenant_id = resource.tenant_id
   AND floor.site_id = resource.site_id
   AND floor.floor_id = resource.floor_id
ON CONFLICT DO NOTHING;

-- Link adjacent active floors through both elevator and stair shafts.
INSERT INTO wp_navigation_edges (
    edge_id, tenant_id, graph_revision_id, from_node_id, to_node_id,
    travel_seconds, travel_mode, bidirectional, accessible, required_permission)
SELECT md5('dwp:v339:edge:elevator:' || current.tenant_id || ':'
           || current.floor_id || ':' || next.floor_id)::uuid,
       current.tenant_id, current.spatial_graph_id,
       current.elevator_node_id, next.elevator_node_id,
       GREATEST(35, 20 + ABS(next.floor_number - current.floor_number) * 8),
       'ELEVATOR', TRUE, TRUE, NULL
  FROM tmp_v339_navigation_floors current
  JOIN tmp_v339_navigation_floors next
    ON next.tenant_id = current.tenant_id
   AND next.site_id = current.site_id
   AND next.floor_order = current.floor_order + 1
UNION ALL
SELECT md5('dwp:v339:edge:stair:' || current.tenant_id || ':'
           || current.floor_id || ':' || next.floor_id)::uuid,
       current.tenant_id, current.spatial_graph_id,
       current.stair_node_id, next.stair_node_id,
       GREATEST(45, ABS(next.floor_number - current.floor_number) * 22),
       'STAIR', TRUE, FALSE, NULL
  FROM tmp_v339_navigation_floors current
  JOIN tmp_v339_navigation_floors next
    ON next.tenant_id = current.tenant_id
   AND next.site_id = current.site_id
   AND next.floor_order = current.floor_order + 1
ON CONFLICT DO NOTHING;

INSERT INTO wp_navigation_pois (
    poi_id, tenant_id, graph_revision_id, node_id, site_id, floor_id,
    resource_id, category, name_ko, name_en,
    direction_hint_ko, direction_hint_en, active)
SELECT md5('dwp:v339:poi:entry:' || seed.tenant_id || ':' || seed.site_id)::uuid,
       seed.tenant_id, seed.spatial_graph_id,
       md5('dwp:v339:node:entry:' || seed.tenant_id || ':' || seed.site_id)::uuid,
       seed.site_id, seed.entry_floor_id, NULL::uuid, 'ENTRY',
       seed.site_name_ko || ' 주 출입구', seed.site_name_en || ' main entrance',
       '주 출입구 안내 표지에서 출발하세요.',
       'Start at the main entrance wayfinding sign.', TRUE
  FROM tmp_v339_navigation_sites seed
ON CONFLICT DO NOTHING;

INSERT INTO wp_navigation_pois (
    poi_id, tenant_id, graph_revision_id, node_id, site_id, floor_id,
    resource_id, category, name_ko, name_en,
    direction_hint_ko, direction_hint_en, active)
SELECT poi_id, tenant_id, spatial_graph_id, node_id, site_id, floor_id,
       resource_id,
       CASE WHEN resource_type = 'ROOM' THEN 'ROOM' ELSE 'OTHER' END,
       name_ko, name_en,
       '도면에 표시된 통로를 따라 이동하세요.',
       'Follow the corridor shown on the floor plan.', TRUE
  FROM tmp_v339_navigation_resources
ON CONFLICT DO NOTHING;

INSERT INTO wp_navigation_pois (
    poi_id, tenant_id, graph_revision_id, node_id, site_id, floor_id,
    resource_id, category, name_ko, name_en,
    direction_hint_ko, direction_hint_en, active)
SELECT poi_id, tenant_id, spatial_graph_id, node_id, site_id, floor_id,
       NULL::uuid, category, name_ko, name_en, hint_ko, hint_en, TRUE
  FROM (
      SELECT md5('dwp:v339:poi:elevator:' || floor.tenant_id || ':'
                 || floor.floor_id)::uuid poi_id,
             floor.tenant_id, floor.spatial_graph_id, floor.elevator_node_id node_id,
             floor.site_id, floor.floor_id, 'ELEVATOR' category,
             floor.floor_number || '층 엘리베이터' name_ko,
             'Floor ' || floor.floor_number || ' elevator' name_en,
             '엘리베이터 승강장으로 이동하세요.' hint_ko,
             'Proceed to the elevator lobby.' hint_en
        FROM tmp_v339_navigation_floors floor
      UNION ALL
      SELECT md5('dwp:v339:poi:stair:' || floor.tenant_id || ':'
                 || floor.floor_id)::uuid,
             floor.tenant_id, floor.spatial_graph_id, floor.stair_node_id,
             floor.site_id, floor.floor_id, 'STAIR',
             floor.floor_number || '층 계단',
             'Floor ' || floor.floor_number || ' stairs',
             '계단 출입구로 이동하세요.', 'Proceed to the stair entrance.'
        FROM tmp_v339_navigation_floors floor
  ) pois
ON CONFLICT DO NOTHING;

-- Publish the new immutable fixture revision, retaining V337 as history.
UPDATE wp_navigation_graph_revisions graph
   SET lifecycle_state = 'ARCHIVED',
       version = graph.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 900018
  FROM tmp_v339_navigation_sites seed
 WHERE graph.tenant_id = seed.tenant_id
   AND graph.graph_revision_id = seed.baseline_graph_id
   AND graph.lifecycle_state = 'PUBLISHED'
   AND graph.version = 1;

UPDATE wp_navigation_graph_revisions graph
   SET lifecycle_state = 'PUBLISHED',
       submitted_at = CURRENT_TIMESTAMP,
       submitted_by = 900018,
       published_at = CURRENT_TIMESTAMP,
       published_by = 900018,
       version = 2,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 900018
  FROM tmp_v339_navigation_sites seed
 WHERE graph.tenant_id = seed.tenant_id
   AND graph.graph_revision_id = seed.spatial_graph_id
   AND graph.lifecycle_state = 'DRAFT'
   AND graph.version = 1;

DROP TABLE pg_temp.tmp_v339_navigation_resources;
DROP TABLE pg_temp.tmp_v339_navigation_floors;
DROP TABLE pg_temp.tmp_v339_navigation_sites;
