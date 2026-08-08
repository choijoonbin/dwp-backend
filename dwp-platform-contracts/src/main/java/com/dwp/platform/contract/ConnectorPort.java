package com.dwp.platform.contract;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface ConnectorPort {

    Manifest manifest();

    Health health(ExecutionContext context);

    ReadPage read(ReadRequest request);

    enum Capability {
        PRODUCTIVITY_READ,
        KNOWLEDGE_READ,
        SERVICE_REQUEST_CREATE,
        LOW_RISK_ACTION
    }

    enum HealthState {
        HEALTHY,
        DEGRADED,
        AUTHENTICATION_REQUIRED,
        UNAVAILABLE
    }

    record Manifest(
            String id,
            String version,
            String owner,
            DataClassification maximumClassification,
            Set<Capability> capabilities) {

        public Manifest {
            id = ContractChecks.required(id, "id");
            version = ContractChecks.required(version, "version");
            owner = ContractChecks.required(owner, "owner");
            if (maximumClassification == null) {
                throw new IllegalArgumentException("maximumClassification is required");
            }
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }

    record Health(
            HealthState state,
            Instant checkedAt,
            Instant lastSuccessfulSyncAt,
            long lagSeconds,
            String errorCode) {

        public Health {
            if (state == null || checkedAt == null) {
                throw new IllegalArgumentException("state and checkedAt are required");
            }
            if (lagSeconds < 0) {
                throw new IllegalArgumentException("lagSeconds must not be negative");
            }
        }
    }

    record ReadRequest(
            ExecutionContext context,
            String cursor,
            int limit,
            Instant changedAfter) {

        public ReadRequest {
            if (context == null) {
                throw new IllegalArgumentException("context is required");
            }
            limit = ContractChecks.limit(limit, 200);
        }
    }

    record Item(
            String sourceId,
            String type,
            String title,
            Instant occurredAt,
            URI sourceUrl,
            String permissionReference,
            DataClassification classification) {

        public Item {
            sourceId = ContractChecks.required(sourceId, "sourceId");
            type = ContractChecks.required(type, "type");
            title = ContractChecks.required(title, "title");
            permissionReference = ContractChecks.required(
                    permissionReference,
                    "permissionReference");
            if (occurredAt == null || sourceUrl == null || classification == null) {
                throw new IllegalArgumentException(
                        "occurredAt, sourceUrl, and classification are required");
            }
        }
    }

    record ReadPage(
            List<Item> items,
            String nextCursor,
            String syncCursor,
            boolean partial) {

        public ReadPage {
            items = items == null ? List.of() : List.copyOf(items);
            syncCursor = ContractChecks.required(syncCursor, "syncCursor");
        }
    }
}
