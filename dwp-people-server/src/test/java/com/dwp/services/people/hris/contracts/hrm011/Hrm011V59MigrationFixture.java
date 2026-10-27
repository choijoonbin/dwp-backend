package com.dwp.services.people.hris.contracts.hrm011;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HRM-011 migration rows, schema assertions, and lifecycle SQL fixtures. */
final class Hrm011V59MigrationFixture {

    static final long TENANT_ONE = 59_001L;
    static final long TENANT_TWO = 59_002L;
    static final String SHA_A = "a".repeat(64);
    static final String SHA_B = "b".repeat(64);
    static final String SHA_C = "c".repeat(64);
    static final List<String> TABLES = List.of(
            "ppl_compensation_basis",
            "ppl_employment_contracts",
            "ppl_worker_restricted_tokens",
            "ppl_dependents");

    static Graph insertGraph(JdbcTemplate jdbc, long tenantId, String key) {
        long person = insertPerson(jdbc, tenantId, key + "-person");
        long worker = jdbc.queryForObject("""
                INSERT INTO ppl_workers(
                    tenant_id,person_id,worker_number,worker_type,worker_status,
                    original_hire_date,version)
                VALUES(?,?,?,'EMPLOYEE','ACTIVE',DATE '2020-01-01',1)
                RETURNING worker_id
                """, Long.class, tenantId, person, "HRM011-" + key);
        long employer = jdbc.queryForObject("""
                INSERT INTO ppl_legal_employers(
                    tenant_id,employer_key,legal_name,version)
                VALUES(?,?,?,1) RETURNING legal_employer_id
                """, Long.class, tenantId, "HRM011-EMP-" + key,
                "HRM-011 Employer " + key);
        long relationship = jdbc.queryForObject("""
                INSERT INTO ppl_work_relationships(
                    tenant_id,relationship_key,worker_id,legal_employer_id,
                    relationship_type,primary_relationship,start_date,version)
                VALUES(?,?,?,?,'EMPLOYEE',TRUE,DATE '2020-01-01',0)
                RETURNING work_relationship_id
                """, Long.class, tenantId, "HRM011-REL-" + key,
                worker, employer);
        long assignment = jdbc.queryForObject("""
                INSERT INTO ppl_assignments(
                    tenant_id,assignment_key,work_relationship_id,
                    effective_start_date,assignment_status,primary_assignment,
                    business_title,version)
                VALUES(?,?,?,DATE '2020-01-01','ACTIVE',TRUE,
                    'HRM-011 Specialist',1)
                RETURNING assignment_id
                """, Long.class, tenantId, "HRM011-ASG-" + key,
                relationship);
        return new Graph(tenantId, person, worker, relationship, assignment);
    }

    static long insertPerson(JdbcTemplate jdbc, long tenantId, String key) {
        return jdbc.queryForObject("""
                INSERT INTO ppl_persons(tenant_id,person_key,display_name,version)
                VALUES(?,?,?,1) RETURNING person_id
                """, Long.class, tenantId, key, "Person " + key);
    }

    static long insertCompensation(
            JdbcTemplate jdbc,
            Graph graph,
            String basisType,
            LocalDate validFrom,
            LocalDate validTo,
            long version) {
        return jdbc.queryForObject(
                compensationInsert(graph, basisType, validFrom, validTo, version)
                        + " RETURNING compensation_basis_id",
                Long.class);
    }

    static String compensationInsert(
            Graph graph,
            String basisType,
            LocalDate validFrom,
            LocalDate validTo,
            long version) {
        return """
                INSERT INTO ppl_compensation_basis(
                    public_id,tenant_id,assignment_id,basis_type,amount,
                    currency_code,frequency_code,valid_from,valid_to,version,
                    created_by,updated_by)
                VALUES(DEFAULT,%d,%d,'%s',1000.000000,'KRW','MONTHLY',
                    DATE '%s',%s,%d,7,7)
                """.formatted(
                graph.tenantId(), graph.assignmentId(), basisType, validFrom,
                validTo == null ? "NULL" : "DATE '" + validTo + "'", version);
    }

    static void insertContract(
            JdbcTemplate jdbc,
            Graph graph,
            long basisId,
            String schemaVersion,
            String lifecycle,
            long version,
            UUID publicId,
            UUID correlationId,
            UUID artifactRequestId) {
        jdbc.update(contractInsert(
                graph, basisId, schemaVersion, lifecycle, version,
                publicId, correlationId, artifactRequestId));
    }

    static String contractInsert(
            Graph graph,
            long basisId,
            String schemaVersion,
            String lifecycle,
            long version,
            UUID publicId,
            UUID correlationId,
            UUID artifactRequestId) {
        return """
                INSERT INTO ppl_employment_contracts(
                    public_id,tenant_id,work_relationship_id,relationship_version,
                    assignment_id,assignment_version,compensation_basis_id,
                    compensation_basis_version,contract_type,lifecycle_state,locale,
                    template_public_id,template_version,
                    template_content_schema_version,template_source_revision,
                    template_published_at,template_payload_digest,
                    template_content_object_public_id,
                    template_content_object_version,template_content_sha256,
                    template_approval_receipt_id,snapshot_basis_type,
                    snapshot_amount,snapshot_currency_code,
                    snapshot_frequency_code,generation_snapshot_digest,
                    artifact_request_id,artifact_provider_receipt_id,
                    document_object_public_id,document_object_version,
                    document_content_sha256,valid_from,correlation_id,version,
                    issued_at,created_by,updated_by)
                VALUES('%s',%d,%d,0,%d,1,%d,1,'EMPLOYMENT','%s','ko-KR',
                    '90000000-0000-4000-8000-000000000001',1,'%s',1,
                    TIMESTAMPTZ '2026-01-01T00:00:00Z','%s',
                    '90000000-0000-4000-8000-000000000002',1,'%s',
                    '90000000-0000-4000-8000-000000000003','SALARY',
                    1000.000000,'KRW','MONTHLY','%s','%s',
                    '90000000-0000-4000-8000-000000000004',
                    '90000000-0000-4000-8000-000000000005',1,'%s',
                    DATE '2026-01-01','%s',%d,
                    TIMESTAMPTZ '2026-01-02T00:00:00Z',7,7)
                """.formatted(
                publicId, graph.tenantId(), graph.relationshipId(),
                graph.assignmentId(), basisId, lifecycle, schemaVersion,
                SHA_A, SHA_B, SHA_C, artifactRequestId, SHA_A,
                correlationId, version);
    }

    static void insertRestrictedToken(
            JdbcTemplate jdbc,
            Graph graph,
            String tokenKind,
            boolean primary,
            LocalDate validFrom,
            LocalDate validTo,
            long version,
            String tokenRef) {
        jdbc.update(restrictedTokenInsert(
                graph, tokenKind, primary, validFrom, validTo, version, tokenRef));
    }

    static String restrictedTokenInsert(
            Graph graph,
            String tokenKind,
            boolean primary,
            LocalDate validFrom,
            LocalDate validTo,
            long version,
            String tokenRef) {
        String payment = tokenKind.equals("PAYMENT_DESTINATION")
                ? "'KRW',1" : "NULL,NULL";
        return """
                INSERT INTO ppl_worker_restricted_tokens(
                    public_id,tenant_id,worker_id,token_kind,purpose_code,
                    jurisdiction_code,vault_provider_key,vault_token_ref,
                    token_revision,masked_display,currency_code,payment_priority,
                    verification_state,primary_for_purpose,valid_from,valid_to,
                    version,created_by,updated_by)
                VALUES(DEFAULT,%d,%d,'%s','PAYROLL','KR','test-vault','%s',
                    1,'***1234',%s,'VERIFIED',%s,DATE '%s',%s,%d,7,7)
                """.formatted(
                graph.tenantId(), graph.workerId(), tokenKind, tokenRef,
                payment, primary ? "TRUE" : "FALSE", validFrom,
                validTo == null ? "NULL" : "DATE '" + validTo + "'", version);
    }

    static void insertDependent(
            JdbcTemplate jdbc,
            Graph graph,
            UUID publicId,
            long version) {
        jdbc.update(dependentInsert(graph, publicId, version));
    }

    static String dependentInsert(Graph graph, UUID publicId, long version) {
        return """
                INSERT INTO ppl_dependents(
                    public_id,tenant_id,person_id,relationship_type,
                    encrypted_profile,profile_schema_version,key_reference,
                    valid_from,version,created_by,updated_by)
                VALUES('%s',%d,%d,'CHILD',decode('01','hex'),1,
                    'test-key-reference',DATE '2026-01-01',%d,7,7)
                """.formatted(publicId, graph.tenantId(), graph.personId(), version);
    }

    static void assertColumns(
            JdbcTemplate jdbc,
            String table,
            List<ColumnSpec> expected) {
        List<ColumnSpec> actual = jdbc.query("""
                SELECT column_name,
                       CASE data_type
                           WHEN 'character varying' THEN
                               'VARCHAR(' || character_maximum_length || ')'
                           WHEN 'character' THEN
                               'CHAR(' || character_maximum_length || ')'
                           WHEN 'numeric' THEN
                               'NUMERIC(' || numeric_precision || ','
                                   || numeric_scale || ')'
                           WHEN 'timestamp with time zone' THEN 'TIMESTAMPTZ'
                           WHEN 'bigint' THEN 'BIGINT'
                           WHEN 'smallint' THEN 'SMALLINT'
                           WHEN 'integer' THEN 'INTEGER'
                           WHEN 'boolean' THEN 'BOOLEAN'
                           WHEN 'date' THEN 'DATE'
                           WHEN 'uuid' THEN 'UUID'
                           WHEN 'bytea' THEN 'BYTEA'
                           WHEN 'text' THEN 'TEXT'
                           ELSE upper(data_type)
                       END AS declared_type,
                       is_nullable = 'NO' AS not_null,
                       column_default
                  FROM information_schema.columns
                 WHERE table_schema='public' AND table_name=?
                 ORDER BY ordinal_position
                """, (result, rowNumber) -> new ColumnSpec(
                result.getString("column_name"),
                result.getString("declared_type"),
                result.getBoolean("not_null"),
                result.getString("column_default")), table);

        assertThat(actual).containsExactlyElementsOf(expected);
    }

    static ColumnSpec col(
            String name,
            String type,
            boolean notNull,
            String defaultExpression) {
        return new ColumnSpec(name, type, notNull, defaultExpression);
    }

    static UUID insertLifecycleContract(
            JdbcTemplate jdbc,
            Graph graph,
            long basisId,
            String lifecycle) {
        UUID publicId = UUID.randomUUID();
        insertContract(
                jdbc, graph, basisId, "PDX.EmploymentContract.v1", lifecycle,
                1L, publicId, UUID.randomUUID(), UUID.randomUUID());
        return publicId;
    }

    static int transition(
            JdbcTemplate jdbc,
            UUID publicId,
            String lifecycle,
            long version,
            boolean signing) {
        if (signing) {
            return jdbc.update("""
                    UPDATE ppl_employment_contracts
                       SET lifecycle_state=?,version=?,
                           signature_case_public_id=COALESCE(
                               signature_case_public_id,?::UUID),
                           signed_at=COALESCE(signed_at,clock_timestamp()),
                           updated_by=8
                     WHERE public_id=?
                    """, lifecycle, version, UUID.randomUUID().toString(), publicId);
        }
        return jdbc.update("""
                UPDATE ppl_employment_contracts
                   SET lifecycle_state=?,version=?,updated_by=8
                 WHERE public_id=?
                """, lifecycle, version, publicId);
    }

    static void assertIllegalTransition(
            JdbcTemplate jdbc,
            UUID publicId,
            String lifecycle,
            long version) {
        assertThatThrownBy(() -> transition(
                jdbc, publicId, lifecycle, version, "SIGNED".equals(lifecycle)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "illegal employment contract lifecycle transition");
    }

    static String withoutContractArtifact(String sql, UUID artifactRequestId) {
        return sql
                .replace("'" + artifactRequestId + "'", "NULL")
                .replace("'90000000-0000-4000-8000-000000000004'", "NULL")
                .replace("'90000000-0000-4000-8000-000000000005'", "NULL")
                .replace("NULL,1,'" + SHA_A + "'", "NULL,NULL,NULL")
                .replace("TIMESTAMPTZ '2026-01-02T00:00:00Z'", "NULL");
    }

    static String withContractSupersedes(String sql, long supersedesId) {
        return sql
                .replace(
                        "document_content_sha256,valid_from,correlation_id,version,",
                        "document_content_sha256,valid_from,supersedes_contract_id,correlation_id,version,")
                .replace(
                        "DATE '2026-01-01','",
                        "DATE '2026-01-01'," + supersedesId + ",'");
    }

    static String withContractValidTo(String sql, String validTo) {
        return sql
                .replace(
                        "document_content_sha256,valid_from,correlation_id,version,",
                        "document_content_sha256,valid_from,valid_to,correlation_id,version,")
                .replace(
                        "DATE '2026-01-01','",
                        "DATE '2026-01-01'," + validTo + ",'");
    }

    static String withDependentValidTo(String sql, String validTo) {
        return sql
                .replace(
                        "valid_from,version,created_by,updated_by",
                        "valid_from,valid_to,version,created_by,updated_by")
                .replace(
                        "DATE '2026-01-01',1,7,7",
                        "DATE '2026-01-01'," + validTo + ",1,7,7");
    }

    static int count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    static void assertRlsRejected(
            Connection connection,
            Statement statement,
            String sql) throws Exception {
        java.sql.Savepoint savepoint = connection.setSavepoint();
        assertThatThrownBy(() -> statement.executeUpdate(sql))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("row-level security policy");
        connection.rollback(savepoint);
    }

    static Map<String, Integer> checksums(Flyway flyway, int maximum) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (MigrationInfo info : flyway.info().applied()) {
            if (info.getVersion() != null
                    && Integer.parseInt(info.getVersion().getVersion()) <= maximum) {
                result.put(info.getVersion().getVersion(), info.getChecksum());
            }
        }
        return result;
    }

    static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    record Graph(
            long tenantId,
            long personId,
            long workerId,
            long relationshipId,
            long assignmentId) {
    }

    record ColumnSpec(
            String name,
            String type,
            boolean notNull,
            String defaultExpression) {
    }
}
