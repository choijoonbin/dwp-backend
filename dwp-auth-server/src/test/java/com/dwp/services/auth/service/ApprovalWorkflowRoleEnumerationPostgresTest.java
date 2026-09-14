package com.dwp.services.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import com.dwp.services.auth.repository.RoleMemberRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class ApprovalWorkflowRoleEnumerationPostgresTest {
    @Test void actualUnionMatchesAuthoritativeCountAndRejectsScopedExpiredProviderMemberships() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE com_users(tenant_id bigint,user_id bigint,status text,identity_plane text)");
            jdbc.execute("CREATE TABLE com_role_members(tenant_id bigint,role_id bigint,user_id bigint)");
            jdbc.execute("CREATE TABLE com_groups(tenant_id bigint,group_id bigint,status text)");
            jdbc.execute("CREATE TABLE com_group_members(tenant_id bigint,group_id bigint,user_id bigint)");
            jdbc.execute("CREATE TABLE com_group_role_assignments(tenant_id bigint,group_id bigint,role_id bigint,lifecycle_state text,assignment_type text,scope_type text,valid_from timestamptz,valid_to timestamptz)");
            jdbc.execute("CREATE TABLE com_active_privileged_grants(tenant_id bigint,role_id bigint,user_id bigint,scope_type text,revoked_at timestamptz,activated_at timestamptz,expires_at timestamptz)");
            jdbc.execute("INSERT INTO com_users SELECT 42,n,'ACTIVE','TENANT' FROM generate_series(1,10)n");
            jdbc.execute("INSERT INTO com_users VALUES(43,11,'ACTIVE','TENANT'),(42,12,'ACTIVE','PROVIDER'),(42,13,'INACTIVE','TENANT')");
            jdbc.execute("INSERT INTO com_role_members VALUES(42,10,1),(42,10,1),(43,10,11),(42,10,12),(42,10,13)");
            jdbc.execute("INSERT INTO com_groups SELECT 42,n,CASE WHEN n=6 THEN 'INACTIVE' ELSE 'ACTIVE' END FROM generate_series(1,6)n");
            jdbc.execute("INSERT INTO com_group_members SELECT 42,n,n+1 FROM generate_series(1,6)n");
            jdbc.execute("INSERT INTO com_group_members VALUES(42,1,1)");
            jdbc.execute("INSERT INTO com_group_role_assignments SELECT 42,n,10,'ACTIVE',CASE WHEN n=3 THEN 'ELIGIBLE' ELSE 'ACTIVE' END,CASE WHEN n=2 THEN 'RESOURCE' ELSE 'TENANT' END,CASE WHEN n=4 THEN now()+interval '1 hour' ELSE now()-interval '1 hour' END,CASE WHEN n=5 THEN now()-interval '1 second' ELSE now()+interval '1 hour' END FROM generate_series(1,6)n");
            jdbc.execute("INSERT INTO com_active_privileged_grants VALUES(42,10,8,'TENANT',NULL,now()-interval '1 hour',now()+interval '1 hour'),(42,10,9,'TENANT',now(),now()-interval '1 hour',now()+interval '1 hour'),(42,10,10,'RESOURCE',NULL,now()-interval '1 hour',now()+interval '1 hour')");
            var named = new NamedParameterJdbcTemplate(source);
            var args = new MapSqlParameterSource().addValue("tenantId", 42L).addValue("roleId", 10L);
            String enumeration = RoleMemberRepository.class.getMethod("enumerateEffectiveActiveUsers", Long.class, Long.class).getAnnotation(Query.class).value();
            String count = RoleMemberRepository.class.getMethod("countEffectiveActiveUsers", Long.class, Long.class).getAnnotation(Query.class).value();
            assertEquals(List.of(1L, 2L, 8L), named.queryForList(enumeration, args, Long.class));
            assertEquals(3L, named.queryForObject(count, args, Long.class));
            jdbc.execute("INSERT INTO com_users SELECT 42,n,'ACTIVE','TENANT' FROM generate_series(100,1200)n");
            jdbc.execute("INSERT INTO com_role_members SELECT 42,10,n FROM generate_series(100,1200)n");
            assertEquals(1001, named.queryForList(enumeration, args, Long.class).size());
            assertEquals(1104L, named.queryForObject(count, args, Long.class));
        }
    }
}
