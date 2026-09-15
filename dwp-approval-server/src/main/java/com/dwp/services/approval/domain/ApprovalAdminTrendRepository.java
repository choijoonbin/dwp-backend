package com.dwp.services.approval.domain;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class ApprovalAdminTrendRepository {

    private final NamedParameterJdbcTemplate jdbc;

    ApprovalAdminTrendRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    ApprovalAdminTrendDtos.Trend load(MapSqlParameterSource params) {
        var generatedAt = new AtomicReference<Instant>();
        List<ApprovalAdminTrendDtos.Bucket> buckets = jdbc.query(
                ApprovalAdminTrendSql01.SELECT,
                params,
                (result, rowNumber) -> {
                    generatedAt.compareAndSet(null, instant(result, "generated_at"));
                    return new ApprovalAdminTrendDtos.Bucket(
                            instant(result, "bucket_start"),
                            instant(result, "bucket_end"),
                            result.getInt("submitted_requests"),
                            result.getInt("completed_requests"),
                            result.getInt("sla_breaches"),
                            result.getInt("unresolved_delivery_updates"),
                            result.getInt("in_flight_requests"),
                            result.getInt("sla_eligible_tasks"));
                });
        return new ApprovalAdminTrendDtos.Trend(
                generatedAt.get() == null ? Instant.now() : generatedAt.get(),
                72,
                6,
                List.copyOf(buckets));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
