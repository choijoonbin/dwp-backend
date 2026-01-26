package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.ApiCallHistory;
import com.dwp.services.auth.repository.projection.LatencyPercentilesView;
import com.dwp.services.auth.repository.projection.TopCauseView;
import com.dwp.services.auth.repository.projection.TopErrorView;
import com.dwp.services.auth.repository.projection.TopSlowView;
import com.dwp.services.auth.repository.projection.TopTrafficView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ApiCallHistoryRepository extends JpaRepository<ApiCallHistory, Long> {
    
    Page<ApiCallHistory> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    @Query("SELECT COUNT(a) FROM ApiCallHistory a WHERE a.tenantId = :tenantId AND a.createdAt BETWEEN :from AND :to")
    long countByTenantIdAndCreatedAtBetween(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(a) FROM ApiCallHistory a WHERE a.tenantId = :tenantId AND a.statusCode >= 400 AND a.createdAt BETWEEN :from AND :to")
    long countErrorsByTenantIdAndCreatedAtBetween(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(a) FROM ApiCallHistory a WHERE a.tenantId = :tenantId AND a.statusCode BETWEEN 200 AND 399 AND a.createdAt BETWEEN :from AND :to")
    long countSuccessByTenantIdAndCreatedAtBetween(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(a) FROM ApiCallHistory a WHERE a.tenantId = :tenantId AND a.statusCode BETWEEN 400 AND 499 AND a.createdAt BETWEEN :from AND :to")
    long count4xxByTenantIdAndCreatedAtBetween(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(a) FROM ApiCallHistory a WHERE a.tenantId = :tenantId AND a.statusCode BETWEEN 500 AND 599 AND a.createdAt BETWEEN :from AND :to")
    long count5xxByTenantIdAndCreatedAtBetween(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 5분 버킷 중 5xx 비율 >= threshold% 인 버킷 수 (downtimeMinutes = result * 5) */
    @Query(value = "SELECT " +
            " COALESCE(SUM(CASE WHEN (bucket_5xx::double precision / NULLIF(bucket_total,0)) * 100.0 >= :threshold THEN 1 ELSE 0 END),0) AS \"downtimeBuckets\" " +
            " FROM ( " +
            " SELECT " +
            "   date_trunc('minute', a.created_at) - (EXTRACT(minute FROM a.created_at)::int % 5) * INTERVAL '1 minute' AS bucket_start, " +
            "   COUNT(*) AS bucket_total, " +
            "   SUM(CASE WHEN a.status_code BETWEEN 500 AND 599 THEN 1 ELSE 0 END) AS bucket_5xx " +
            " FROM sys_api_call_histories a " +
            " WHERE a.tenant_id = :tenantId AND a.created_at >= :from AND a.created_at <= :to " +
            " GROUP BY bucket_start " +
            " ) t", nativeQuery = true)
    long countDowntimeBuckets(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("threshold") double threshold);

    /** 1분 단위 버킷 중 (분당 요청 수 >= minReqPerMinute 이고 5xx 에러율 > threshold%) 버킷 개수 (downtimeMinutes = 반환값) */
    @Query(value = "SELECT COALESCE(COUNT(*),0) FROM (" +
            " SELECT date_trunc('minute', a.created_at) AS bucket_start, " +
            "   COUNT(*) AS bucket_total, " +
            "   SUM(CASE WHEN a.status_code BETWEEN 500 AND 599 THEN 1 ELSE 0 END) AS bucket_5xx " +
            " FROM sys_api_call_histories a " +
            " WHERE a.tenant_id = :tenantId AND a.created_at >= :from AND a.created_at <= :to " +
            " GROUP BY date_trunc('minute', a.created_at)" +
            " HAVING COUNT(*) >= :minReqPerMinute " +
            "   AND (SUM(CASE WHEN a.status_code BETWEEN 500 AND 599 THEN 1 ELSE 0 END)::double precision / NULLIF(COUNT(*),0)) * 100.0 > :threshold" +
            " ) t", nativeQuery = true)
    long countDowntimeBuckets1Min(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                  @Param("minReqPerMinute") int minReqPerMinute, @Param("threshold") double threshold);

    /** 1분 단위 버킷 중 (분당 요청 수 >= minReqPerMinute 이고 5xx 에러율 > threshold%) 버킷의 시작 시각 목록 (차트 장애 구간 표시용) */
    @Query(value = "SELECT t.bucket_start FROM (" +
            " SELECT date_trunc('minute', a.created_at) AS bucket_start, " +
            "   COUNT(*) AS bucket_total, " +
            "   SUM(CASE WHEN a.status_code BETWEEN 500 AND 599 THEN 1 ELSE 0 END) AS bucket_5xx " +
            " FROM sys_api_call_histories a " +
            " WHERE a.tenant_id = :tenantId AND a.created_at >= :from AND a.created_at <= :to " +
            " GROUP BY date_trunc('minute', a.created_at)" +
            " HAVING COUNT(*) >= :minReqPerMinute " +
            "   AND (SUM(CASE WHEN a.status_code BETWEEN 500 AND 599 THEN 1 ELSE 0 END)::double precision / NULLIF(COUNT(*),0)) * 100.0 > :threshold" +
            " ) t ORDER BY t.bucket_start", nativeQuery = true)
    List<Object[]> findDowntimeBucketStarts(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                            @Param("minReqPerMinute") int minReqPerMinute, @Param("threshold") double threshold);

    /** 5xx top path: path별 건수 LIMIT 1, alias 고정 */
    @Query(value = "SELECT a.path AS \"path\", COUNT(*) AS \"count\" " +
            " FROM sys_api_call_histories a " +
            " WHERE a.tenant_id = :tenantId AND a.created_at >= :from AND a.created_at <= :to " +
            " AND a.status_code BETWEEN 500 AND 599 " +
            " GROUP BY a.path ORDER BY COUNT(*) DESC LIMIT 1", nativeQuery = true)
    List<TopCauseView> findTop5xxPath(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** p50, p95, p99 (PostgreSQL ordered-set aggregate, 1행 고정, alias 고정) */
    @Query(value = "SELECT " +
            " percentile_cont(0.5) WITHIN GROUP (ORDER BY a.latency_ms) AS \"p50Ms\", " +
            " percentile_cont(0.95) WITHIN GROUP (ORDER BY a.latency_ms) AS \"p95Ms\", " +
            " percentile_cont(0.99) WITHIN GROUP (ORDER BY a.latency_ms) AS \"p99Ms\" " +
            " FROM sys_api_call_histories a " +
            " WHERE a.tenant_id = :tenantId AND a.created_at >= :from AND a.created_at <= :to AND a.latency_ms IS NOT NULL", nativeQuery = true)
    LatencyPercentilesView findLatencyPercentiles(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 내 평균 지연 시간(ms). latency_ms NULL 제외, 데이터 없으면 null */
    @Query("SELECT AVG(a.latencyMs) FROM ApiCallHistory a WHERE a.tenantId = :tenantId AND a.createdAt BETWEEN :from AND :to AND a.latencyMs IS NOT NULL")
    Double findAvgLatencyMs(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** path별 p95 최대 1건, ORDER BY p95_ms DESC NULLS LAST */
    @Query(value = "SELECT t.path AS \"path\", t.p95_ms AS \"p95Ms\" FROM ( " +
            " SELECT a.path AS path, " +
            " percentile_cont(0.95) WITHIN GROUP (ORDER BY a.latency_ms) AS p95_ms " +
            " FROM sys_api_call_histories a " +
            " WHERE a.tenant_id = :tenantId AND a.created_at >= :from AND a.created_at <= :to AND a.latency_ms IS NOT NULL " +
            " GROUP BY a.path " +
            " ) t ORDER BY t.p95_ms DESC NULLS LAST LIMIT 1", nativeQuery = true)
    List<TopSlowView> findTopSlowPath(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 분당 요청 수 최대값 (rpsPeak): 1분 버킷별 count, max 반환 */
    @Query(value = "SELECT COALESCE(MAX(bucket_count), 0) FROM (" +
            " SELECT COUNT(*) AS bucket_count FROM sys_api_call_histories a " +
            " WHERE a.tenant_id = :tenantId AND a.created_at >= :from AND a.created_at <= :to " +
            " GROUP BY date_trunc('minute', a.created_at)" +
            ") AS buckets", nativeQuery = true)
    Long findMaxCountPerMinute(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** path별 요청 수 최대 1건, alias 고정 */
    @Query(value = "SELECT a.path AS \"path\", COUNT(*) AS \"requestCount\" " +
            " FROM sys_api_call_histories a " +
            " WHERE a.tenant_id = :tenantId AND a.created_at >= :from AND a.created_at <= :to " +
            " GROUP BY a.path ORDER BY COUNT(*) DESC LIMIT 1", nativeQuery = true)
    List<TopTrafficView> findTopTrafficPath(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** (path, status_code) 별 건수 최대 1건, status_code >= 400, alias 고정 */
    @Query(value = "SELECT a.path AS \"path\", a.status_code AS \"statusCode\", COUNT(*) AS \"count\" " +
            " FROM sys_api_call_histories a " +
            " WHERE a.tenant_id = :tenantId AND a.created_at >= :from AND a.created_at <= :to AND a.status_code >= 400 " +
            " GROUP BY a.path, a.status_code ORDER BY COUNT(*) DESC LIMIT 1", nativeQuery = true)
    List<TopErrorView> findTopErrorPathAndStatus(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 시계열 1분 버킷 (interval=1m). P95 정확성: 구간 내 전체 모수에 대해 percentile_cont(0.95) 사용. 단순 AVG 금지.
     * 반환: bucket_ts, total, count_4xx, count_5xx, count_success, p50, p95, p99.
     */
    @Query(value = "SELECT date_trunc('minute', created_at)," +
            " COUNT(*)," +
            " COUNT(*) FILTER (WHERE status_code >= 400 AND status_code < 500)," +
            " COUNT(*) FILTER (WHERE status_code >= 500 AND status_code < 600)," +
            " COUNT(*) FILTER (WHERE status_code >= 200 AND status_code < 400)," +
            " (percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL))::bigint," +
            " (percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL))::bigint," +
            " (percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL))::bigint " +
            " FROM sys_api_call_histories " +
            " WHERE tenant_id = :tenantId AND created_at >= :from AND created_at <= :to " +
            " GROUP BY date_trunc('minute', created_at) ORDER BY 1", nativeQuery = true)
    List<Object[]> findTimeseriesBucketStatsMinute(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 시계열 5분 버킷 (interval=5m). P95: 구간 전체 모수 percentile_cont(0.95). 단순 평균 아님.
     * 버킷 = date_trunc('hour') + (minute/5)*5분.
     */
    @Query(value = "SELECT date_trunc('hour', created_at) + (EXTRACT(MINUTE FROM created_at)::int / 5) * INTERVAL '5 min'," +
            " COUNT(*)," +
            " COUNT(*) FILTER (WHERE status_code >= 400 AND status_code < 500)," +
            " COUNT(*) FILTER (WHERE status_code >= 500 AND status_code < 600)," +
            " COUNT(*) FILTER (WHERE status_code >= 200 AND status_code < 400)," +
            " (percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL))::bigint," +
            " (percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL))::bigint," +
            " (percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL))::bigint " +
            " FROM sys_api_call_histories " +
            " WHERE tenant_id = :tenantId AND created_at >= :from AND created_at <= :to " +
            " GROUP BY date_trunc('hour', created_at) + (EXTRACT(MINUTE FROM created_at)::int / 5) * INTERVAL '5 min' ORDER BY 1", nativeQuery = true)
    List<Object[]> findTimeseriesBucketStats5Min(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 시계열 1시간 버킷 (interval=1h). P95: 구간 내 전체 응답시간에서 percentile_cont(0.95). 단순 AVG 사용 금지.
     */
    @Query(value = "SELECT date_trunc('hour', created_at)," +
            " COUNT(*)," +
            " COUNT(*) FILTER (WHERE status_code >= 400 AND status_code < 500)," +
            " COUNT(*) FILTER (WHERE status_code >= 500 AND status_code < 600)," +
            " COUNT(*) FILTER (WHERE status_code >= 200 AND status_code < 400)," +
            " (percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL))::bigint," +
            " (percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL))::bigint," +
            " (percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL))::bigint " +
            " FROM sys_api_call_histories " +
            " WHERE tenant_id = :tenantId AND created_at >= :from AND created_at <= :to " +
            " GROUP BY date_trunc('hour', created_at) ORDER BY 1", nativeQuery = true)
    List<Object[]> findTimeseriesBucketStatsHour(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 시계열 1일 버킷 (interval=1d). P95: 구간 전체 모수에서 percentile_cont(0.95). 단순 평균 아님.
     */
    @Query(value = "SELECT date_trunc('day', created_at)," +
            " COUNT(*)," +
            " COUNT(*) FILTER (WHERE status_code >= 400 AND status_code < 500)," +
            " COUNT(*) FILTER (WHERE status_code >= 500 AND status_code < 600)," +
            " COUNT(*) FILTER (WHERE status_code >= 200 AND status_code < 400)," +
            " (percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL))::bigint," +
            " (percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL))::bigint," +
            " (percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL))::bigint " +
            " FROM sys_api_call_histories " +
            " WHERE tenant_id = :tenantId AND created_at >= :from AND created_at <= :to " +
            " GROUP BY date_trunc('day', created_at) ORDER BY 1", nativeQuery = true)
    List<Object[]> findTimeseriesBucketStatsDay(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
    
    /**
     * API 호출 이력 필터링 조회 (날짜 범위 없음)
     */
    @Query("SELECT a FROM ApiCallHistory a " +
           "WHERE a.tenantId = :tenantId " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(a.path) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(a.method) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:apiName IS NULL OR :apiName = '' OR LOWER(a.path) LIKE LOWER(CONCAT('%', :apiName, '%'))) " +
           "AND (:apiUrl IS NULL OR :apiUrl = '' OR LOWER(a.path) LIKE LOWER(CONCAT('%', :apiUrl, '%'))) " +
           "AND (:statusCode IS NULL OR a.statusCode = :statusCode) " +
           "AND (:userId IS NULL OR a.userId = :userId) " +
           "ORDER BY a.createdAt DESC")
    Page<ApiCallHistory> findByTenantIdAndFiltersWithoutDate(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            @Param("apiName") String apiName,
            @Param("apiUrl") String apiUrl,
            @Param("statusCode") Integer statusCode,
            @Param("userId") Long userId,
            Pageable pageable);
    
    /**
     * API 호출 이력 필터링 조회 (날짜 범위 포함)
     */
    @Query("SELECT a FROM ApiCallHistory a " +
           "WHERE a.tenantId = :tenantId " +
           "AND a.createdAt >= :from " +
           "AND a.createdAt <= :to " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(a.path) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(a.method) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:apiName IS NULL OR :apiName = '' OR LOWER(a.path) LIKE LOWER(CONCAT('%', :apiName, '%'))) " +
           "AND (:apiUrl IS NULL OR :apiUrl = '' OR LOWER(a.path) LIKE LOWER(CONCAT('%', :apiUrl, '%'))) " +
           "AND (:statusCode IS NULL OR a.statusCode = :statusCode) " +
           "AND (:userId IS NULL OR a.userId = :userId)")
    Page<ApiCallHistory> findByTenantIdAndFiltersWithDate(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("keyword") String keyword,
            @Param("apiName") String apiName,
            @Param("apiUrl") String apiUrl,
            @Param("statusCode") Integer statusCode,
            @Param("userId") Long userId,
            Pageable pageable);

    /**
     * API 호출 이력 필터링 조회 (드릴다운: statusGroup, path, minLatencyMs, maxLatencyMs, sort)
     * statusGroup: 2xx(200-299), 3xx(300-399), 4xx(400-499), 5xx(500-599)
     * sort: TIME_DESC(default), LATENCY_DESC, COUNT_DESC(→ TIME_DESC fallback)
     */
    @Query("SELECT a FROM ApiCallHistory a " +
           "WHERE a.tenantId = :tenantId " +
           "AND a.createdAt >= :from AND a.createdAt <= :to " +
           "AND (:keyword IS NULL OR :keyword = '' OR LOWER(a.path) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(a.method) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:apiName IS NULL OR :apiName = '' OR LOWER(a.path) LIKE LOWER(CONCAT('%', :apiName, '%'))) " +
           "AND (:apiUrl IS NULL OR :apiUrl = '' OR LOWER(a.path) LIKE LOWER(CONCAT('%', :apiUrl, '%'))) " +
           "AND (:pathLike IS NULL OR :pathLike = '' OR LOWER(a.path) LIKE LOWER(CONCAT('%', :pathLike, '%'))) " +
           "AND (:statusCode IS NULL OR a.statusCode = :statusCode) " +
           "AND (:statusGroup IS NULL OR :statusGroup = '' OR " +
           "     (:statusGroup = '2xx' AND a.statusCode >= 200 AND a.statusCode < 300) OR " +
           "     (:statusGroup = '3xx' AND a.statusCode >= 300 AND a.statusCode < 400) OR " +
           "     (:statusGroup = '4xx' AND a.statusCode >= 400 AND a.statusCode < 500) OR " +
           "     (:statusGroup = '5xx' AND a.statusCode >= 500 AND a.statusCode < 600)) " +
           "AND (:minLatencyMs IS NULL OR a.latencyMs >= :minLatencyMs) " +
           "AND (:maxLatencyMs IS NULL OR a.latencyMs <= :maxLatencyMs) " +
           "AND (:userId IS NULL OR a.userId = :userId)")
    Page<ApiCallHistory> findByTenantIdAndFiltersWithDateAndDrillDown(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("keyword") String keyword,
            @Param("apiName") String apiName,
            @Param("apiUrl") String apiUrl,
            @Param("pathLike") String pathLike,
            @Param("statusCode") Integer statusCode,
            @Param("statusGroup") String statusGroup,
            @Param("minLatencyMs") Long minLatencyMs,
            @Param("maxLatencyMs") Long maxLatencyMs,
            @Param("userId") Long userId,
            Pageable pageable);
}
