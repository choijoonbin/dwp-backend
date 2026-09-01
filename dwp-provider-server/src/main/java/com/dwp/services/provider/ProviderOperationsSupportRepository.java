package com.dwp.services.provider;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

final class ProviderOperationsSupportRepository {

    private final JdbcTemplate jdbc;

    ProviderOperationsSupportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    ProviderOperationsRepository.SupportPolicy supportPolicy(Set<String> scopes) {
        if (scopes.isEmpty()) return new ProviderOperationsRepository.SupportPolicy("L1", false, 0);
        String placeholders = String.join(",", scopes.stream().map(ignored -> "?").toList());
        Object[] arguments = scopes.toArray();
        return jdbc.query("""
                SELECT CASE MAX(CASE risk_tier WHEN 'L3' THEN 3 WHEN 'L2' THEN 2 ELSE 1 END)
                           WHEN 3 THEN 'L3' WHEN 2 THEN 'L2' ELSE 'L1'
                       END AS risk_tier,
                       BOOL_OR(requires_customer_approval) AS requires_customer_approval,
                       COUNT(*) AS matched_scopes
                  FROM prv_support_scope_catalog
                 WHERE lifecycle_state = 'ACTIVE'
                   AND scope_code IN (""" + placeholders + ")",
                (result, ignored) -> new ProviderOperationsRepository.SupportPolicy(
                        result.getString("risk_tier"),
                        result.getBoolean("requires_customer_approval"),
                        result.getInt("matched_scopes")), arguments)
                .stream().findFirst().orElse(new ProviderOperationsRepository.SupportPolicy("L1", false, 0));
    }

    List<ProviderDtos.SupportScopeSummary> supportScopes() {
        return jdbc.query("""
                SELECT scope_code, display_name, risk_tier,
                       requires_customer_approval, lifecycle_state
                  FROM prv_support_scope_catalog
                 WHERE lifecycle_state = 'ACTIVE'
                 ORDER BY CASE risk_tier WHEN 'L1' THEN 1 WHEN 'L2' THEN 2 ELSE 3 END,
                          scope_code
                """, (result, ignored) -> new ProviderDtos.SupportScopeSummary(
                result.getString("scope_code"), result.getString("display_name"),
                result.getString("risk_tier"), result.getBoolean("requires_customer_approval"),
                result.getString("lifecycle_state")));
    }
}
