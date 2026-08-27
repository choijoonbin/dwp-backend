package com.dwp.services.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.jackson.io.JacksonDeserializer;
import io.jsonwebtoken.jackson.io.JacksonSerializer;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Process-wide session token encoder with explicitly configured JSON codecs.
 *
 * <p>JJWT 0.12.3 dynamically discovered its JSON implementation through a
 * non-thread-safe {@code ServiceLoader} path. A singleton codec and parser keep
 * token issuance independent of dynamic provider discovery and make the first
 * concurrent login equivalent to all subsequent logins.</p>
 */
@Component
public final class AuthSessionJwtTokenEncoder {

    private static final UUID STARTUP_PROBE_FAMILY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final SecretKey signingKey;
    private final JacksonSerializer<Map<String, ?>> serializer;
    private final JwtParser parser;

    public AuthSessionJwtTokenEncoder(
            @Value("${jwt.secret}") String jwtSecret,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(jwtSecret, "jwtSecret");
        Objects.requireNonNull(objectMapper, "objectMapper");
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));

        ObjectMapper tokenObjectMapper = objectMapper.copy();
        this.serializer = new JacksonSerializer<>(tokenObjectMapper);
        this.parser = Jwts.parser()
                .verifyWith(signingKey)
                .json(new JacksonDeserializer<>(tokenObjectMapper))
                .build();
    }

    public String encode(SessionTokenClaims claims) {
        Objects.requireNonNull(claims, "claims");
        JwtBuilder builder = Jwts.builder()
                .json(serializer)
                .id(claims.tokenId())
                .subject(String.valueOf(claims.userId()))
                .claim("tenant_id", String.valueOf(claims.tenantId()))
                .claim("roles", claims.roles())
                .claim("sid", claims.familyId().toString())
                .issuedAt(Date.from(claims.issuedAt()))
                .expiration(Date.from(claims.expiresAt()));
        if (claims.authenticatedAt() != null && claims.acr() != null) {
            builder.claim("auth_time", claims.authenticatedAt().getEpochSecond())
                    .claim("acr", claims.acr())
                    .claim("amr", claims.amr());
        }
        return builder.signWith(signingKey, Jwts.SIG.HS256).compact();
    }

    Claims decodeAndVerify(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    public void verifyStartupReadiness() {
        Instant issuedAt = Instant.now();
        SessionTokenClaims probe = new SessionTokenClaims(
                0L,
                0L,
                List.of("STARTUP_PROBE"),
                "startup-probe",
                STARTUP_PROBE_FAMILY_ID,
                issuedAt,
                issuedAt.plusSeconds(60),
                issuedAt,
                "urn:dwp:acr:startup-probe",
                List.of("startup-probe"));
        Claims decoded = decodeAndVerify(encode(probe));
        if (!probe.tokenId().equals(decoded.getId())
                || !String.valueOf(probe.userId()).equals(decoded.getSubject())
                || !String.valueOf(probe.tenantId()).equals(decoded.get("tenant_id", String.class))
                || !probe.familyId().toString().equals(decoded.get("sid", String.class))) {
            throw new IllegalStateException(
                    "JWT startup probe did not preserve the signed session claims.");
        }
    }

    public record SessionTokenClaims(
            Long userId,
            Long tenantId,
            List<String> roles,
            String tokenId,
            UUID familyId,
            Instant issuedAt,
            Instant expiresAt,
            Instant authenticatedAt,
            String acr,
            List<String> amr) {

        public SessionTokenClaims {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(tenantId, "tenantId");
            roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
            Objects.requireNonNull(tokenId, "tokenId");
            Objects.requireNonNull(familyId, "familyId");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            amr = amr == null ? List.of() : List.copyOf(amr);
        }
    }
}
