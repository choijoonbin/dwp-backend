package com.dwp.services.platform.workplace.connectorops;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.Capability;
import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.ConnectorKind;
import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.ProviderReportedState;

/**
 * In-process source for the bundled SKAX verification tenant.
 *
 * <p>The adapter is intentionally unusable for arbitrary tenants. Readiness requires the exact
 * provider/reference pair and the exact verification identity that owns the bundled fixture.
 * Customer tenants and production connector configurations therefore remain fail-closed. It
 * emits connector health and checkpoint evidence only; it never fabricates a person-presence or
 * occupancy projection.</p>
 */
@Component
public final class BundledVerificationPresenceAdapter
        implements WorkplaceConnectorReplayAdapter {
    static final String PROVIDER = "DWP_LOCAL_VERIFICATION";
    static final String CONFIGURATION_REFERENCE = "local://skax-verification/presence";
    static final long VERIFICATION_USER_ID = 900018L;
    static final String VERIFICATION_PERSON_ID = "8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public BundledVerificationPresenceAdapter(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    BundledVerificationPresenceAdapter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean supports(String provider, ConnectorKind kind) {
        return PROVIDER.equals(provider) && kind == ConnectorKind.ACTUAL_PRESENCE;
    }

    @Override
    public boolean ready(ProviderContext context) {
        if (context == null
                || !supports(context.provider(), context.kind())
                || !CONFIGURATION_REFERENCE.equals(context.credentialReference())) {
            return false;
        }
        Boolean bundledIdentity = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM sys_service_tenants tenant
                      JOIN cal_identity_links identity_link
                        ON identity_link.tenant_id = tenant.tenant_id
                       AND identity_link.user_id = ?
                       AND identity_link.person_public_id = ?::uuid
                     WHERE tenant.tenant_id = ?
                       AND tenant.tenant_key = 'default'
                )
                """, Boolean.class, VERIFICATION_USER_ID, VERIFICATION_PERSON_ID,
                context.tenantId());
        return Boolean.TRUE.equals(bundledIdentity);
    }

    @Override
    public RuntimeObservation observe(ProviderContext context) {
        if (!ready(context)) {
            throw new IllegalStateException(
                    "The bundled verification presence adapter is not ready for this tenant.");
        }
        OffsetDateTime observedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        long minute = observedAt.toEpochSecond() / 60;
        return new RuntimeObservation(
                ProviderReportedState.HEALTHY,
                List.of(Capability.HEALTH, Capability.CHECKPOINT),
                observedAt,
                observedAt,
                0L,
                "local-verification-presence:" + minute,
                0L,
                0L,
                null,
                "dwp-bundled-verification-presence",
                "1",
                "skax_presence_" + minute);
    }
}
