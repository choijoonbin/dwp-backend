package com.dwp.platform.contract.home;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Shared fail-closed validation for owner-service Home provider transports. */
public final class HomeWidgetProviderSecurity {

    public static final String TRUSTED_SERVICE_IDENTITY = "dwp-platform-server";

    private HomeWidgetProviderSecurity() {
    }

    public static Context authorize(String expectedToken, Evidence evidence) {
        String configured = normalized(expectedToken, 512);
        if (configured == null || configured.length() < 24) {
            fail(HomeWidgetProviderRequestException.Kind.SERVICE_UNAVAILABLE,
                    "HOME_PROVIDER_IDENTITY_NOT_CONFIGURED",
                    "The dedicated Home Runtime provider identity is not configured.");
        }
        if (evidence.duplicateProtectedHeaders()) {
            fail(HomeWidgetProviderRequestException.Kind.BAD_REQUEST,
                    "HOME_PROVIDER_HEADER_DUPLICATED",
                    "Security-sensitive Home provider headers must occur exactly once.");
        }
        if (!TRUSTED_SERVICE_IDENTITY.equals(evidence.serviceIdentity())
                || !constantTimeEquals(configured, evidence.serviceToken())) {
            fail(HomeWidgetProviderRequestException.Kind.UNAUTHORIZED,
                    "HOME_PROVIDER_IDENTITY_INVALID",
                    "A trusted Home Runtime provider identity is required.");
        }
        if (present(evidence.authorization()) || present(evidence.cookie())
                || present(evidence.supportSessionId())
                || present(evidence.providerTenantId())
                || present(evidence.actorTenantId())) {
            fail(HomeWidgetProviderRequestException.Kind.FORBIDDEN,
                    "HOME_PROVIDER_AMBIENT_AUTHORITY_REJECTED",
                    "Ambient browser or support authority is not accepted by a Home provider.");
        }
        long tenantId = positiveLong(evidence.tenantId());
        long userId = positiveLong(evidence.userId());
        UUID personPublicId = optionalUuid(evidence.personPublicId());
        String revision = normalized(evidence.authorityDecisionRevision(), 200);
        if (revision == null || !revision.matches("[A-Za-z0-9._:@+-]{1,200}")) {
            fail(HomeWidgetProviderRequestException.Kind.UNAUTHORIZED,
                    "HOME_PROVIDER_AUTHORITY_REVISION_INVALID",
                    "A current authority decision revision is required.");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime revalidateAt = future(evidence.authorityRevalidateAt(), now,
                "HOME_PROVIDER_AUTHORITY_EXPIRED");
        OffsetDateTime deadlineAt = future(evidence.deadlineAt(), now,
                "HOME_PROVIDER_DEADLINE_EXPIRED");
        if (deadlineAt.isAfter(now.plusSeconds(30))) {
            fail(HomeWidgetProviderRequestException.Kind.BAD_REQUEST,
                    "HOME_PROVIDER_DEADLINE_UNBOUNDED",
                    "The provider deadline exceeds the transport budget.");
        }
        return new Context(
                tenantId,
                userId,
                personPublicId,
                parse(evidence.permissions()),
                parse(evidence.roles()),
                parse(evidence.groups()),
                revision,
                revalidateAt,
                deadlineAt,
                locale(evidence.locale()));
    }

    private static long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException | NullPointerException ignored) {
            // Mapped to a transport-safe failure below.
        }
        fail(HomeWidgetProviderRequestException.Kind.UNAUTHORIZED,
                "HOME_PROVIDER_RECIPIENT_INVALID",
                "A positive recipient tenant and user are required.");
        return 0;
    }

    private static UUID optionalUuid(String value) {
        if (!present(value)) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            fail(HomeWidgetProviderRequestException.Kind.UNAUTHORIZED,
                    "HOME_PROVIDER_RECIPIENT_INVALID",
                    "The recipient person binding is invalid.");
            return null;
        }
    }

    private static OffsetDateTime future(String value, OffsetDateTime now, String code) {
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            if (parsed.isAfter(now)) return parsed;
        } catch (DateTimeParseException | NullPointerException ignored) {
            // Mapped to a transport-safe failure below.
        }
        fail(HomeWidgetProviderRequestException.Kind.UNAUTHORIZED, code,
                "Current recipient authority evidence is missing or expired.");
        return now;
    }

    private static Set<String> parse(String value) {
        if (!present(value)) return Set.of();
        Set<String> values = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(candidate -> candidate.matches("[A-Za-z0-9._:@+-]{1,160}"))
                .limit(256)
                .collect(Collectors.toUnmodifiableSet());
        if (values.size() != Arrays.stream(value.split(","))
                .map(String::trim).filter(candidate -> !candidate.isBlank()).count()) {
            fail(HomeWidgetProviderRequestException.Kind.BAD_REQUEST,
                    "HOME_PROVIDER_AUTHORITY_SET_INVALID",
                    "Recipient authority sets must be bounded canonical identifiers.");
        }
        return values;
    }

    private static String locale(String value) {
        String normalized = normalized(value, 35);
        return normalized != null && normalized.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
                ? normalized : "ko-KR";
    }

    private static String normalized(String value, int maximum) {
        if (!present(value)) return null;
        String normalized = value.trim();
        return normalized.length() <= maximum ? normalized : null;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void fail(
            HomeWidgetProviderRequestException.Kind kind,
            String reasonCode,
            String message) {
        throw new HomeWidgetProviderRequestException(kind, reasonCode, message);
    }

    public record Evidence(
            String serviceIdentity,
            String serviceToken,
            String tenantId,
            String userId,
            String personPublicId,
            String permissions,
            String roles,
            String groups,
            String authorityDecisionRevision,
            String authorityRevalidateAt,
            String deadlineAt,
            String locale,
            String authorization,
            String cookie,
            String supportSessionId,
            String providerTenantId,
            String actorTenantId,
            boolean duplicateProtectedHeaders) {
    }

    public record Context(
            long tenantId,
            long userId,
            UUID personPublicId,
            Set<String> permissions,
            Set<String> roles,
            Set<String> groups,
            String authorityDecisionRevision,
            OffsetDateTime authorityRevalidateAt,
            OffsetDateTime deadlineAt,
            String locale) {

        public boolean has(String resourceKey, String... actions) {
            return Arrays.stream(actions)
                    .anyMatch(action -> permissions.contains(resourceKey + ":" + action));
        }
    }
}
