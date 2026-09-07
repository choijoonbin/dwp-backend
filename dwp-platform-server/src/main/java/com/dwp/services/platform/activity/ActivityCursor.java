package com.dwp.services.platform.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Opaque position, not an authorization token. Every query rechecks source ACL. */
@Component
public class ActivityCursor {
    private final ObjectMapper mapper;
    public ActivityCursor(ObjectMapper mapper) { this.mapper = mapper; }

    public String scope(Long tenantId, Long actorId, Set<String> permissions, String locale, ActivityQuery q) {
        try {
            String canonical = mapper.writeValueAsString(List.of(
                    tenantId, actorId, permissions.stream().sorted().toList(), value(locale),
                    value(q.actor()), value(q.state()), value(q.query()), value(q.source()),
                    value(q.objectType()), value(q.objectId()), value(q.executionId()),
                    value(q.from()), value(q.to()), q.includeUsage()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot bind activity query.", exception);
        }
    }

    public Position decode(String token, String scope) {
        if (token == null) return new Position(OffsetDateTime.now(java.time.ZoneOffset.UTC), null, null, scope);
        try {
            if (token.length() > 2048) throw ActivityQuery.invalid();
            Position result = mapper.readValue(Base64.getUrlDecoder().decode(token), Position.class);
            if (!scope.equals(result.scope()) || result.snapshotAt() == null
                    || (result.occurredAt() == null) != (result.id() == null)
                    || result.snapshotAt().isAfter(OffsetDateTime.now().plusSeconds(1))) {
                throw ActivityQuery.invalid();
            }
            return result;
        } catch (Exception exception) {
            throw ActivityQuery.invalid();
        }
    }

    public String encode(Position position, OffsetDateTime occurredAt, UUID id) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(
                    new Position(position.snapshotAt(), occurredAt, id, position.scope())));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot encode activity cursor.", exception);
        }
    }

    private String value(Object value) { return value == null ? "" : value.toString(); }
    public record Position(OffsetDateTime snapshotAt, OffsetDateTime occurredAt, UUID id, String scope) {}
}
