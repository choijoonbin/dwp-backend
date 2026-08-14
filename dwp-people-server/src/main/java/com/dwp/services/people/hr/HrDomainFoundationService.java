package com.dwp.services.people.hr;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HrDomainFoundationService {

    private final JdbcTemplate jdbc;

    public HrDomainFoundationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensure(Long tenantId) {
        jdbc.query("SELECT seed_hr_domain_foundation(?)", resultSet -> {
            // The PostgreSQL function returns void; consuming the row avoids JDBC type coercion.
        }, tenantId);
    }
}
