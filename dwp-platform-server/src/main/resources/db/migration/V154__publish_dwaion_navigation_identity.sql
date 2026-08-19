CREATE FUNCTION dwp_migrate_dwaion_navigation_tree(payload jsonb)
RETURNS jsonb
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT COALESCE(
        jsonb_agg(
            jsonb_set(
                root_node,
                '{children}',
                COALESCE(
                    (
                        SELECT jsonb_agg(
                            CASE
                                WHEN child_node->>'navigationKey' = 'ask' THEN
                                    jsonb_set(
                                        jsonb_set(
                                            child_node,
                                            '{route}',
                                            to_jsonb('/dwaion'::text),
                                            true
                                        ),
                                        '{labels}',
                                        COALESCE(
                                            (
                                                SELECT jsonb_agg(
                                                    label_node || jsonb_build_object(
                                                        'label', 'DWAI·ON',
                                                        'description', CASE label_node->>'locale'
                                                            WHEN 'ko' THEN '권한 범위의 업무 맥락과 근거를 연결하는 AI'
                                                            ELSE 'Permission-aware AI connecting work context with evidence'
                                                        END
                                                    )
                                                    ORDER BY label_order
                                                )
                                                  FROM jsonb_array_elements(
                                                      COALESCE(child_node->'labels', '[]'::jsonb)
                                                  ) WITH ORDINALITY AS labels(label_node, label_order)
                                            ),
                                            '[]'::jsonb
                                        ),
                                        true
                                    )
                                ELSE child_node
                            END
                            ORDER BY child_order
                        )
                          FROM jsonb_array_elements(
                              COALESCE(root_node->'children', '[]'::jsonb)
                          ) WITH ORDINALITY AS children(child_node, child_order)
                    ),
                    '[]'::jsonb
                ),
                true
            )
            ORDER BY root_order
        ),
        '[]'::jsonb
    )
      FROM jsonb_array_elements(COALESCE(payload, '[]'::jsonb))
           WITH ORDINALITY AS roots(root_node, root_order);
$$;

WITH latest_published AS (
    SELECT DISTINCT ON (tenant_id)
           navigation_revision_id,
           tenant_id,
           tree_payload,
           validation_payload,
           published_by
      FROM adm_navigation_revisions
     WHERE lifecycle_state = 'PUBLISHED'
     ORDER BY tenant_id, revision_number DESC
), changed AS (
    SELECT published.*,
           dwp_migrate_dwaion_navigation_tree(published.tree_payload) AS next_tree
      FROM latest_published AS published
     WHERE dwp_migrate_dwaion_navigation_tree(published.tree_payload)
           <> published.tree_payload
), superseded AS (
    UPDATE adm_navigation_revisions AS revision
       SET lifecycle_state = 'SUPERSEDED',
           version = version + 1,
           updated_at = CURRENT_TIMESTAMP,
           updated_by = COALESCE(changed.published_by, 1)
      FROM changed
     WHERE revision.navigation_revision_id = changed.navigation_revision_id
    RETURNING changed.tenant_id,
              changed.navigation_revision_id AS baseline_revision_id,
              changed.next_tree,
              changed.validation_payload,
              COALESCE(changed.published_by, 1) AS actor_id
)
INSERT INTO adm_navigation_revisions (
    navigation_revision_id,
    tenant_id,
    revision_number,
    lifecycle_state,
    baseline_revision_id,
    baseline_tree_hash,
    tree_payload,
    validation_payload,
    change_summary,
    published_at,
    published_by,
    created_at,
    created_by,
    updated_at,
    updated_by
)
SELECT gen_random_uuid(),
       superseded.tenant_id,
       (
           SELECT COALESCE(MAX(history.revision_number), 0) + 1
             FROM adm_navigation_revisions AS history
            WHERE history.tenant_id = superseded.tenant_id
       ),
       'PUBLISHED',
       superseded.baseline_revision_id,
       encode(sha256(convert_to(superseded.next_tree::text, 'UTF8')), 'hex'),
       superseded.next_tree,
       superseded.validation_payload,
       'Publish DWAI·ON product identity and canonical workspace route',
       CURRENT_TIMESTAMP,
       superseded.actor_id,
       CURRENT_TIMESTAMP,
       superseded.actor_id,
       CURRENT_TIMESTAMP,
       superseded.actor_id
  FROM superseded;

UPDATE adm_navigation_revisions AS draft
   SET tree_payload = dwp_migrate_dwaion_navigation_tree(draft.tree_payload),
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = COALESCE(draft.updated_by, draft.created_by, 1)
 WHERE draft.lifecycle_state = 'DRAFT'
   AND dwp_migrate_dwaion_navigation_tree(draft.tree_payload) <> draft.tree_payload;

DROP FUNCTION dwp_migrate_dwaion_navigation_tree(jsonb);
