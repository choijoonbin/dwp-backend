package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.domain.NotificationAppSummaryModels.AppNotificationCounter;
import com.dwp.services.notification.domain.NotificationAppSummaryModels.AppNotificationSummary;
import com.dwp.services.notification.domain.NotificationAppSummaryRepository.AppSummaryMetadata;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationAppSummaryService {

    static final int MAX_APP_SUMMARIES = 100;
    static final String PROJECTION_UNAVAILABLE = "USER_NOTIFICATION_PROJECTION";
    static final String SUMMARY_LIMIT_REACHED = "APP_SUMMARY_LIMIT_REACHED";

    private static final Logger log =
            LoggerFactory.getLogger(NotificationAppSummaryService.class);

    private final NotificationDatabaseScope databaseScope;
    private final NotificationAppSummaryRepository repository;
    private final Clock clock;

    @Autowired
    public NotificationAppSummaryService(
            NotificationDatabaseScope databaseScope,
            NotificationAppSummaryRepository repository) {
        this(databaseScope, repository, Clock.systemUTC());
    }

    NotificationAppSummaryService(
            NotificationDatabaseScope databaseScope,
            NotificationAppSummaryRepository repository,
            Clock clock) {
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AppNotificationSummary summary(NotificationRequestContext.Actor actor) {
        databaseScope.applyUser(actor);
        AppSummaryMetadata metadata = repository.metadata(actor).orElse(null);
        long version = metadata == null ? 0 : metadata.version();
        Instant generatedAt = Instant.now(clock);
        List<AppNotificationCounter> rows;
        try {
            rows = repository.unreadByApp(actor, MAX_APP_SUMMARIES + 1);
        } catch (DataAccessException exception) {
            log.warn("Notification app summary projection is temporarily unavailable", exception);
            return response(
                    true,
                    List.of(PROJECTION_UNAVAILABLE),
                    List.of(),
                    version,
                    generatedAt);
        }

        boolean truncated = rows.size() > MAX_APP_SUMMARIES;
        List<AppNotificationCounter> apps = truncated
                ? List.copyOf(rows.subList(0, MAX_APP_SUMMARIES))
                : List.copyOf(rows);
        List<String> unavailableSources = new ArrayList<>();
        if (truncated) unavailableSources.add(SUMMARY_LIMIT_REACHED);
        return response(
                truncated,
                unavailableSources,
                apps,
                version,
                generatedAt);
    }

    private AppNotificationSummary response(
            boolean partial,
            List<String> unavailableSources,
            List<AppNotificationCounter> apps,
            long version,
            Instant generatedAt) {
        String externalVersion = NotificationVersionCodec.external(version);
        return new AppNotificationSummary(
                partial,
                unavailableSources,
                apps,
                externalVersion,
                externalVersion,
                generatedAt);
    }
}
