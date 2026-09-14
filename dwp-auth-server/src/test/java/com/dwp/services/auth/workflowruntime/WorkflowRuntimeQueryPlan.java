package com.dwp.services.auth.workflowruntime;

import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;

public final class WorkflowRuntimeQueryPlan {
    private WorkflowRuntimeQueryPlan() { }
    public static void print(JdbcTemplate jdbc, long tenant, Collection<Long> users) {
        var params = new MapSqlParameterSource("tenant", tenant).addValue("users", new SqlArrayValue("bigint", users.toArray()));
        new NamedParameterJdbcTemplate(jdbc).queryForList("EXPLAIN " + WorkflowRuntimeSourceSql.MEMBERSHIPS, params, String.class)
                .forEach(System.out::println);
    }
}
