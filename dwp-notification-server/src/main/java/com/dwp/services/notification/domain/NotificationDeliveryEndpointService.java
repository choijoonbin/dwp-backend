package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationDeliveryEndpointModels.DeliveryEndpoint;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class NotificationDeliveryEndpointService {

    private final NotificationDatabaseScope databaseScope;
    private final NotificationDeliveryEndpointRepository repository;

    public NotificationDeliveryEndpointService(
            NotificationDatabaseScope databaseScope,
            NotificationDeliveryEndpointRepository repository) {
        this.databaseScope = databaseScope;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<DeliveryEndpoint> list(NotificationRequestContext.Actor actor) {
        databaseScope.applyUser(actor);
        return repository.list(actor);
    }

    @Transactional
    public DeliveryEndpoint revoke(
            NotificationRequestContext.Actor actor,
            UUID endpointId,
            long expectedVersion,
            String idempotencyKey) {
        databaseScope.applyUser(actor);
        return repository.revoke(actor, endpointId, expectedVersion, idempotencyKey);
    }
}
