package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceResourceFavoriteDtos.*;

@Service
public class WorkplaceResourceFavoriteService {
    private final WorkplaceResourceFavoriteRepository repository;
    private final Clock clock;

    @Autowired
    public WorkplaceResourceFavoriteService(WorkplaceResourceFavoriteRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WorkplaceResourceFavoriteService(WorkplaceResourceFavoriteRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<FavoriteView> favorites(long tenantId, long userId, List<UUID> resourceIds) {
        requireIdentity(tenantId, userId);
        List<UUID> distinct = List.copyOf(new LinkedHashSet<>(resourceIds == null
                ? List.of() : resourceIds));
        if (distinct.size() > 200) throw invalid("At most 200 resource ids may be queried.");
        return repository.favorites(tenantId, userId, distinct);
    }

    @Transactional
    public FavoriteReceipt set(long tenantId, long userId, UUID resourceId,
                               String idempotencyKey, String correlationId,
                               SetFavoriteRequest request) {
        requireIdentity(tenantId, userId);
        String key = bounded(idempotencyKey, 160, "A valid Idempotency-Key is required.");
        String correlation = optionalBounded(correlationId, 160);
        String fingerprint = sha256(resourceId + "|" + request.favorite() + "|"
                + request.expectedVersion());
        var replay = repository.command(tenantId, userId, key);
        if (replay.isPresent()) {
            if (!MessageDigest.isEqual(replay.get().fingerprint().getBytes(StandardCharsets.US_ASCII),
                    fingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw conflict("The Idempotency-Key was already used for a different favorite command.");
            }
            return replay.get().receipt();
        }
        repository.lock(tenantId, userId, resourceId);
        replay = repository.command(tenantId, userId, key);
        if (replay.isPresent()) {
            if (!replay.get().fingerprint().equals(fingerprint)) {
                throw conflict("The Idempotency-Key was already used for a different favorite command.");
            }
            return replay.get().receipt();
        }
        if (!repository.resourceExists(tenantId, resourceId)) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The Workplace resource is unavailable in this tenant.");
        }
        FavoriteView current = repository.favorite(tenantId, userId, resourceId)
                .orElse(new FavoriteView(resourceId, false, 0, null));
        if (current.version() != request.expectedVersion()) {
            throw conflict("The resource favorite changed. Refresh and review the current value.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        repository.save(tenantId, userId, resourceId, request.favorite(),
                request.expectedVersion(), now);
        FavoriteView result = new FavoriteView(resourceId, request.favorite(),
                current.version() + 1, now);
        UUID auditId = repository.audit(tenantId, userId, resourceId, request.favorite(),
                result.version(), correlation, now);
        UUID commandId = repository.insertCommand(tenantId, userId, resourceId, key,
                fingerprint, result, auditId, correlation, now);
        return new FavoriteReceipt(commandId, result, auditId, correlation, now);
    }

    private static void requireIdentity(long tenantId, long userId) {
        if (tenantId < 1 || userId < 1) throw invalid("A valid tenant and user are required.");
    }

    private static String bounded(String value, int maximum, String message) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw invalid(message);
        }
        return value;
    }

    private static String optionalBounded(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        return bounded(value, maximum, "The correlation id is invalid.");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
