package com.dwp.services.platform.servicecenter;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.security.PlatformRoutePredicateEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.dwp.services.platform.servicecenter.ServiceCenterTypes.RequestStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class ServiceInformationResponsePostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ServiceCenterRepository repository;
    private PlatformAuditService audit;
    private ServiceCenterService service;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new ServiceCenterRepository(jdbc, new ObjectMapper());
        // Only the tenant/catalog registry prerequisites; all service tables use shipped migrations.
        jdbc.execute("CREATE TABLE sys_service_tenants (tenant_id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO sys_service_tenants VALUES (7)");
        jdbc.execute("""
                CREATE TABLE adm_workspace_apps (app_key TEXT, name_ko TEXT, name_en TEXT,
                  description_ko TEXT, description_en TEXT, owner_name TEXT, category TEXT,
                  launch_mode TEXT, launch_target TEXT, icon_key TEXT, health_state TEXT,
                  lifecycle_state TEXT, version BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT)
                """);
        for (String migration : List.of("V62__create_employee_service_center.sql",
                "V227__record_service_requester_information_responses.sql")) {
            jdbc.execute(new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8));
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE svc_request_information_responses, svc_request_timeline, svc_requests");
        audit = mock(PlatformAuditService.class);
        service = new ServiceCenterService(repository, audit, mock(PlatformRoutePredicateEvaluator.class));
    }

    @Test
    void persistsValidatedSchemaResponseWithVersionTimelineAndExactReplay() {
        UUID id = waitingRequest();
        var input = response(UUID.randomUUID());
        var result = tx(() -> service.respondToInformationRequest(7L, 11L, "corr", id, input));
        assertThat(result.request().status()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(result.request().version()).isEqualTo(4);
        assertThat(result.values()).containsExactlyInAnyOrderEntriesOf(input.values());
        assertThat(result.timeline()).hasSize(1);
        assertThat(result.timeline().getFirst().note()).isEqualTo(input.message());
        assertThat(tx(() -> service.respondToInformationRequest(7L, 11L, "retry", id, input)).request().version()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM svc_request_information_responses", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM svc_request_timeline", Integer.class)).isOne();
        verify(audit, times(1)).success(eq(7L), eq(11L), eq("service.request.information.responded"), any(), any(), any(), any(), any());
        assertCode(() -> tx(() -> service.respondToInformationRequest(7L, 11L, null, id,
                new ServiceCenterDtos.InformationResponseRequest(input.values(), "Changed command message", 3L, input.idempotencyKey()))), ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    void simultaneousSameCommandHasOneTransitionAndOneReceipt() throws Exception {
        UUID id = waitingRequest();
        var input = response(UUID.randomUUID());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Long> command = () -> {
                start.await(10, TimeUnit.SECONDS);
                return tx(() -> service.respondToInformationRequest(7L, 11L, null, id, input)).request().version();
            };
            var first = executor.submit(command);
            var second = executor.submit(command);
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(4);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(4);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM svc_request_information_responses", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM svc_request_timeline", Integer.class)).isOne();
        verify(audit, times(1)).success(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deniesOtherRequesterTenantAndInvalidSchemaWithoutPersistentMutation() {
        UUID id = waitingRequest();
        var input = response(UUID.randomUUID());
        assertCode(() -> tx(() -> service.respondToInformationRequest(7L, 12L, null, id, input)), ErrorCode.FORBIDDEN);
        assertCode(() -> tx(() -> service.respondToInformationRequest(8L, 11L, null, id, input)), ErrorCode.NOT_FOUND);
        assertCode(() -> tx(() -> service.respondToInformationRequest(7L, 11L, null, id,
                new ServiceCenterDtos.InformationResponseRequest(Map.of("unknown", "value"), input.message(), 3L, UUID.randomUUID()))), ErrorCode.INVALID_INPUT_VALUE);
        assertThat(repository.findRequest(7L, id).orElseThrow().status()).isEqualTo(RequestStatus.AWAITING_REQUESTER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM svc_request_information_responses", Integer.class)).isZero();
        verifyNoInteractions(audit);
    }

    @Test
    void auditFailureRollsBackResponseTimelineAndReceipt() {
        UUID id = waitingRequest();
        doThrow(new IllegalStateException("Audit unavailable")).when(audit)
                .success(any(), any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> tx(() -> service.respondToInformationRequest(7L, 11L, null, id, response(UUID.randomUUID()))))
                .isInstanceOf(IllegalStateException.class);
        var current = repository.findRequest(7L, id).orElseThrow();
        assertThat(current.status()).isEqualTo(RequestStatus.AWAITING_REQUESTER);
        assertThat(current.version()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM svc_request_timeline", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM svc_request_information_responses", Integer.class)).isZero();
    }

    private UUID waitingRequest() {
        var definition = repository.definition(7L, "technology.software-access").orElseThrow();
        var row = tx(() -> repository.insertRequest(7L, 11L, definition, "Need design software",
                Map.of("softwareName", "Design tools", "businessReason", "Project work"), UUID.randomUUID(), true));
        jdbc.update("UPDATE svc_requests SET status = 'AWAITING_REQUESTER', version = 3 WHERE service_request_id = ?", row.requestId());
        return row.requestId();
    }

    private ServiceCenterDtos.InformationResponseRequest response(UUID commandId) {
        return new ServiceCenterDtos.InformationResponseRequest(
                Map.of("softwareName", "Design tools", "businessReason", "Monthly design and review"),
                "Requested evidence is supplied for monthly reporting", 3L, commandId);
    }

    private <T> T tx(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
