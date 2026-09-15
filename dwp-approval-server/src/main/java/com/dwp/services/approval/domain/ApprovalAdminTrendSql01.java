package com.dwp.services.approval.domain;

final class ApprovalAdminTrendSql01 {

    private ApprovalAdminTrendSql01() {
    }

    static final String SELECT = """
        WITH bounds AS (
            SELECT CURRENT_TIMESTAMP AS generated_at,
                   date_bin(
                       INTERVAL '6 hours',
                       CURRENT_TIMESTAMP,
                       TIMESTAMPTZ '2000-01-01 00:00:00+00') + INTERVAL '6 hours' AS window_end
        ), trend_window AS (
            SELECT generated_at,
                   window_end,
                   window_end - INTERVAL '72 hours' AS window_start
              FROM bounds
        ), buckets AS (
            SELECT trend_window.generated_at,
                   trend_window.window_start,
                   bucket_start,
                   bucket_start + INTERVAL '6 hours' AS bucket_end
              FROM trend_window
              CROSS JOIN LATERAL generate_series(
                  trend_window.window_start,
                  trend_window.window_end - INTERVAL '6 hours',
                  INTERVAL '6 hours') bucket_start
        ), submitted AS (
            SELECT date_bin(INTERVAL '6 hours', request.submitted_at, trend_window.window_start) AS bucket_start,
                   COUNT(*)::BIGINT AS item_count
              FROM apr_requests request
              CROSS JOIN trend_window
             WHERE request.tenant_id = :tenantId
               AND request.management_resource_set_key = :managementScope
               AND request.submitted_at >= trend_window.window_start
               AND request.submitted_at < trend_window.window_end
             GROUP BY 1
        ), completed AS (
            SELECT date_bin(INTERVAL '6 hours', request.completed_at, trend_window.window_start) AS bucket_start,
                   COUNT(*)::BIGINT AS item_count
              FROM apr_requests request
              CROSS JOIN trend_window
             WHERE request.tenant_id = :tenantId
               AND request.management_resource_set_key = :managementScope
               AND request.status IN ('APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED')
               AND request.completed_at >= trend_window.window_start
               AND request.completed_at < trend_window.window_end
             GROUP BY 1
        ), in_flight_baseline AS (
            SELECT COUNT(*)::BIGINT AS item_count
              FROM apr_requests request
              CROSS JOIN trend_window
             WHERE request.tenant_id = :tenantId
               AND request.management_resource_set_key = :managementScope
               AND request.submitted_at < trend_window.window_start
               AND (request.completed_at IS NULL OR request.completed_at >= trend_window.window_start)
        ), sla AS (
            SELECT date_bin(INTERVAL '6 hours', task.due_at, trend_window.window_start) AS bucket_start,
                   COUNT(*)::BIGINT AS eligible_count,
                   COUNT(*) FILTER (WHERE
                       task.status IN ('PENDING', 'CLAIMED')
                       OR (task.status IN ('APPROVED', 'REJECTED') AND task.completed_at > task.due_at)
                   )::BIGINT AS breach_count
              FROM apr_tasks task
              CROSS JOIN trend_window
             WHERE task.tenant_id = :tenantId
               AND task.status IN ('PENDING', 'CLAIMED', 'APPROVED', 'REJECTED')
               AND task.due_at >= trend_window.window_start
               AND task.due_at < trend_window.window_end
               AND task.due_at < trend_window.generated_at
               AND EXISTS (
                   SELECT 1
                     FROM apr_requests request
                    WHERE request.tenant_id = task.tenant_id
                      AND request.request_id = task.request_id
                      AND request.management_resource_set_key = :managementScope)
             GROUP BY 1
        ), unresolved_delivery AS (
            SELECT date_bin(INTERVAL '6 hours', delivery.updated_at, trend_window.window_start) AS bucket_start,
                   COUNT(*)::BIGINT AS item_count
              FROM apr_integration_outbox delivery
              CROSS JOIN trend_window
             WHERE delivery.tenant_id = :tenantId
               AND delivery.management_resource_set_key = :managementScope
               AND delivery.status IN ('FAILED', 'DEAD')
               AND delivery.updated_at >= trend_window.window_start
               AND delivery.updated_at < trend_window.window_end
             GROUP BY 1
        ), series AS (
            SELECT bucket.generated_at,
                   bucket.bucket_start,
                   bucket.bucket_end,
                   COALESCE(submitted.item_count, 0) AS submitted_requests,
                   COALESCE(completed.item_count, 0) AS completed_requests,
                   COALESCE(sla.breach_count, 0) AS sla_breaches,
                   COALESCE(sla.eligible_count, 0) AS sla_eligible_tasks,
                   COALESCE(unresolved_delivery.item_count, 0) AS unresolved_delivery_updates,
                   in_flight_baseline.item_count
                       + SUM(COALESCE(submitted.item_count, 0) - COALESCE(completed.item_count, 0))
                           OVER (ORDER BY bucket.bucket_start) AS in_flight_requests
              FROM buckets bucket
              CROSS JOIN in_flight_baseline
              LEFT JOIN submitted ON submitted.bucket_start = bucket.bucket_start
              LEFT JOIN completed ON completed.bucket_start = bucket.bucket_start
              LEFT JOIN sla ON sla.bucket_start = bucket.bucket_start
              LEFT JOIN unresolved_delivery ON unresolved_delivery.bucket_start = bucket.bucket_start
        )
        SELECT generated_at,
               bucket_start,
               bucket_end,
               LEAST(submitted_requests, 2147483647)::INTEGER AS submitted_requests,
               LEAST(completed_requests, 2147483647)::INTEGER AS completed_requests,
               LEAST(sla_breaches, 2147483647)::INTEGER AS sla_breaches,
               LEAST(sla_eligible_tasks, 2147483647)::INTEGER AS sla_eligible_tasks,
               LEAST(unresolved_delivery_updates, 2147483647)::INTEGER AS unresolved_delivery_updates,
               LEAST(GREATEST(in_flight_requests, 0), 2147483647)::INTEGER AS in_flight_requests
          FROM series
         ORDER BY bucket_start
        """;
}
