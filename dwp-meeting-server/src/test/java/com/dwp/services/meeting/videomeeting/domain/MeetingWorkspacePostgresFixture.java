package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.*;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

@Testcontainers(disabledWithoutDocker = true)
abstract class MeetingWorkspacePostgresFixture {
    abstract PostgreSQLContainer<?> postgres();
    PGSimpleDataSource dataSource;
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    ObjectMapper mapper;
    VideoMeetingAuditRecorder audit;
    VideoMeetingRepository meetings;
    MeetingTemplateRepository templateRepository;
    MeetingTemplateService templates;
    MeetingPersonalRoomService rooms;
    MeetingPreferencesService preferences;
    VideoMeetingService meetingService;
    VideoMeetingScheduleRepository scheduleRepository;
    VideoMeetingScheduleService schedules;
    VideoMeetingPreparationService preparation;
    MeetingPreparationMaterialRetentionService materialRetention;
    MeetingMediaProvider media;

    @BeforeEach
    void migrateWorkspace() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres().getJdbcUrl());
        dataSource.setUser(postgres().getUsername());
        dataSource.setPassword(postgres().getPassword());
        var flyway = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        mapper = new ObjectMapper().findAndRegisterModules();
        audit = spy(new VideoMeetingAuditRecorder(new AuditOutboxRecorder(
                new NamedParameterJdbcTemplate(dataSource), mapper, "dwp-meeting-server", "test", "test")));
        meetings = new VideoMeetingRepository(jdbc, mapper);
        var commands = new MeetingWorkspaceCommands(jdbc, mapper);
        templateRepository = new MeetingTemplateRepository(jdbc, mapper);
        templates = new MeetingTemplateService(templateRepository, commands, meetings, audit);
        preferences = new MeetingPreferencesService(new MeetingPreferencesRepository(jdbc), commands, audit);
        media = mock(MeetingMediaProvider.class);
        meetingService = new VideoMeetingService(meetings, media,
                new MeetingJoinCodeGenerator(new MeetingMediaProperties()), audit, Clock.systemUTC());
        scheduleRepository = new VideoMeetingScheduleRepository(jdbc, mapper);
        schedules = new VideoMeetingScheduleService(
                meetingService, meetings, scheduleRepository, audit);
        materialRetention = new MeetingPreparationMaterialRetentionService(
                new MeetingPreparationMaterialRetentionTransactions(jdbc));
        preparation = new VideoMeetingPreparationService(
                meetings, new VideoMeetingPreparationRepository(jdbc), audit, scheduleRepository,
                materialRetention);
        rooms = new MeetingPersonalRoomService(new MeetingPersonalRoomRepository(jdbc), commands,
                meetingService, meetings, audit);
    }

    <T> T as(long tenant, long user, Set<String> permissions, Supplier<T> command) {
        MeetingRequestContext.set(new MeetingRequestContext.Subject(user, tenant, null, "Test actor",
                Set.of("WORKSPACE_MEMBER"), permissions, Set.of()));
        try {
            return transaction.execute(status -> command.get());
        } finally {
            MeetingRequestContext.clear();
        }
    }

    <T> T own(Supplier<T> command) { return as(1, 3, all(), command); }

    Set<String> all() {
        return Set.of("APP.MEETINGS:VIEW", "APP.MEETINGS:CREATE", "APP.MEETINGS:UPDATE",
                "APP.MEETINGS:DELETE", "ADMIN.MEETINGS:VIEW", "ADMIN.MEETINGS:MANAGE");
    }

    TemplateInput input(String name) {
        return new TemplateInput(name, "Confidential preparation content", "PLANNING", 30,
                List.of(new AgendaItem("Decision", "Private objective", "Facilitator", 15),
                        new AgendaItem("Next steps", "Assign owners", "Team", 15)));
    }

    long count(String table) {
        if (!table.matches("vm_[a-z_]+|sys_audit_outbox")) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
