package com.dwp.services.platform.auditcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditControlRepository extends AuditControlExportIntegrityRepository {
    public AuditControlRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }
}
