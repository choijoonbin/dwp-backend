package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.HrisRecipientAuthorityDtos;
import com.dwp.services.auth.repository.HrisRecipientAuthorityRepository;
import com.dwp.services.auth.repository.HrisRecipientAuthorityRepository.AuthorityRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Resolves a short-lived, purpose-bound person-to-user authority result. */
@Service
public class HrisRecipientAuthorityService {

    static final String DOMAIN_SEPARATOR = "dwp:hris-recipient-authority:v1";
    static final Duration LEASE = Duration.ofSeconds(30);

    private final HrisRecipientAuthorityRepository repository;
    private final Clock clock;

    @Autowired
    public HrisRecipientAuthorityService(HrisRecipientAuthorityRepository repository) {
        this(repository, Clock.systemUTC());
    }

    HrisRecipientAuthorityService(
            HrisRecipientAuthorityRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public HrisRecipientAuthorityDtos.ResolveResponse resolve(
            HrisRecipientAuthorityDtos.ResolveRequest request) {
        if (!HrisRecipientAuthorityDtos.valid(request)) {
            throw unavailable();
        }
        UUID requestId;
        UUID personPublicId;
        try {
            requestId = UUID.fromString(request.requestId());
            personPublicId = UUID.fromString(request.personPublicId());
        } catch (IllegalArgumentException exception) {
            throw unavailable();
        }

        List<AuthorityRow> candidates;
        try {
            candidates = repository.resolve(request.tenantId(), personPublicId);
        } catch (RuntimeException exception) {
            throw unavailable();
        }
        if (candidates == null || candidates.size() != 1) {
            throw unavailable();
        }
        AuthorityRow authority = candidates.getFirst();
        if (!valid(authority, request.tenantId(), personPublicId)) {
            throw unavailable();
        }

        Instant capturedAt = clock.instant();
        return new HrisRecipientAuthorityDtos.ResolveResponse(
                requestId,
                request.tenantId(),
                personPublicId,
                authority.userId(),
                revision(authority),
                capturedAt,
                capturedAt.plus(LEASE));
    }

    private boolean valid(AuthorityRow row, long tenantId, UUID personPublicId) {
        return row != null
                && row.tenantId() != null && row.tenantId() == tenantId
                && row.tenantPublicId() != null
                && "ACTIVE".equals(row.tenantStatus())
                && nonnegative(row.tenantVersion())
                && positive(row.userId())
                && row.principalPublicId() != null
                && personPublicId.equals(row.personPublicId())
                && "ACTIVE".equals(row.userStatus())
                && "TENANT".equals(row.identityPlane())
                && nonnegative(row.userRowVersion())
                && nonnegative(row.accessRevision());
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static boolean nonnegative(Long value) {
        return value != null && value >= 0;
    }

    private String revision(AuthorityRow row) {
        String material = String.join("\n", List.of(
                DOMAIN_SEPARATOR,
                Long.toString(row.tenantId()),
                row.tenantPublicId().toString(),
                row.tenantStatus(),
                Long.toString(row.tenantVersion()),
                Long.toString(row.userId()),
                row.principalPublicId().toString(),
                row.personPublicId().toString(),
                row.userStatus(),
                row.identityPlane(),
                Long.toString(row.userRowVersion()),
                Long.toString(row.accessRevision())));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public static RecipientAuthorityUnavailableException unavailable() {
        return new RecipientAuthorityUnavailableException();
    }

    public static final class RecipientAuthorityUnavailableException
            extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public RecipientAuthorityUnavailableException() {
            super("Recipient authority is unavailable.");
        }
    }
}
