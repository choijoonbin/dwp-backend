package com.dwp.services.synapsex.service.voucher;

import com.dwp.services.synapsex.dto.voucher.MyVoucherPageResponse;
import com.dwp.services.synapsex.dto.voucher.MyVoucherRowDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MyVoucherQueryService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public MyVoucherPageResponse findMyVouchers(Long tenantId, Long userId, String statusFilter, int page, int size, String sort) {
        String normalizedFilter = normalizeStatusFilter(statusFilter);
        String normalizedSort = normalizeSort(sort);
        String orderDirection = normalizedSort.endsWith(",asc") ? "ASC" : "DESC";
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));

        String countSql = """
                SELECT COUNT(*)
                FROM dwp_aura.fi_doc_header h
                WHERE h.tenant_id = :tenantId
                  AND h.user_id = :userId
                  AND (:statusFilter <> 'PENDING_EXPLANATION' OR EXISTS (
                      SELECT 1
                      FROM dwp_aura.agent_case c
                      WHERE c.tenant_id = h.tenant_id
                        AND c.bukrs = h.bukrs
                        AND c.belnr = h.belnr
                        AND c.gjahr = h.gjahr
                        AND c.status::text = 'PENDING_EXPLANATION'
                  ))
                """;

        String dataSql = """
                WITH doc_page AS (
                    SELECT h.tenant_id, h.bukrs, h.belnr, h.gjahr, h.budat, h.waers, h.bktxt
                    FROM dwp_aura.fi_doc_header h
                    WHERE h.tenant_id = :tenantId
                      AND h.user_id = :userId
                      AND (:statusFilter <> 'PENDING_EXPLANATION' OR EXISTS (
                          SELECT 1
                          FROM dwp_aura.agent_case c
                          WHERE c.tenant_id = h.tenant_id
                            AND c.bukrs = h.bukrs
                            AND c.belnr = h.belnr
                            AND c.gjahr = h.gjahr
                            AND c.status::text = 'PENDING_EXPLANATION'
                      ))
                    ORDER BY h.budat %s, h.belnr %s
                    LIMIT :limit OFFSET :offset
                )
                SELECT d.bukrs,
                       d.belnr,
                       d.gjahr,
                       d.budat AS posting_date,
                       COALESCE(li.total_wrbtr, 0) AS wrbtr,
                       d.waers,
                       d.bktxt,
                       lc.case_id,
                       COALESCE(lc.status::text, 'NORMAL') AS case_status,
                       lc.score,
                       lc.detected_at
                FROM doc_page d
                LEFT JOIN LATERAL (
                    SELECT SUM(i.wrbtr) AS total_wrbtr
                    FROM dwp_aura.fi_doc_item i
                    WHERE i.tenant_id = d.tenant_id
                      AND i.bukrs = d.bukrs
                      AND i.belnr = d.belnr
                      AND i.gjahr = d.gjahr
                ) li ON TRUE
                LEFT JOIN LATERAL (
                    SELECT c.case_id, c.status, c.score, c.detected_at
                    FROM dwp_aura.agent_case c
                    WHERE c.tenant_id = d.tenant_id
                      AND c.bukrs = d.bukrs
                      AND c.belnr = d.belnr
                      AND c.gjahr = d.gjahr
                    ORDER BY c.detected_at DESC NULLS LAST, c.case_id DESC
                    LIMIT 1
                ) lc ON TRUE
                ORDER BY d.budat %s, d.belnr %s
                """.formatted(orderDirection, orderDirection, orderDirection, orderDirection);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userId", userId)
                .addValue("statusFilter", normalizedFilter)
                .addValue("limit", safeSize)
                .addValue("offset", (long) safePage * safeSize);

        Long total = jdbcTemplate.queryForObject(countSql, params, Long.class);
        long totalElements = total != null ? total : 0L;
        int totalPages = (int) Math.ceil(totalElements / (double) safeSize);

        List<MyVoucherRowDto> content = jdbcTemplate.query(dataSql, params, (rs, rowNum) ->
                MyVoucherRowDto.builder()
                        .bukrs(rs.getString("bukrs"))
                        .belnr(rs.getString("belnr"))
                        .gjahr(rs.getString("gjahr"))
                        .postingDate(rs.getDate("posting_date") != null ? rs.getDate("posting_date").toLocalDate() : null)
                        .wrbtr(rs.getBigDecimal("wrbtr"))
                        .waers(rs.getString("waers"))
                        .bktxt(rs.getString("bktxt"))
                        .caseId(rs.getObject("case_id") != null ? rs.getLong("case_id") : null)
                        .caseStatus(rs.getString("case_status"))
                        .score(rs.getBigDecimal("score"))
                        .detectedAt(rs.getTimestamp("detected_at") != null ? rs.getTimestamp("detected_at").toInstant() : null)
                        .build());

        return MyVoucherPageResponse.builder()
                .content(content)
                .page(safePage)
                .size(safeSize)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .hasNext((long) (safePage + 1) * safeSize < totalElements)
                .sort(normalizedSort)
                .build();
    }

    private static String normalizeStatusFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return "ALL";
        }
        return "PENDING_EXPLANATION".equalsIgnoreCase(statusFilter) ? "PENDING_EXPLANATION" : "ALL";
    }

    private static String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "postingDate,desc";
        }
        String value = sort.trim().toLowerCase();
        if (!value.startsWith("postingdate")) {
            return "postingDate,desc";
        }
        return value.endsWith(",asc") ? "postingDate,asc" : "postingDate,desc";
    }
}
