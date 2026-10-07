package com.dwp.core.database.authority;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/**
 * Runtime-only signed startup protocol; not Spring wiring, a signer, native inspection implementation
 * or a current deployment fence. Production producer/transport/8-service wiring/13-stream bootstrap
 * and continuous readiness renewal/drain are NOT_IMPLEMENTED. Missing providers always reject.
 * It never opens a migration datasource, executes Flyway, grants history SELECT or creates endpoints.
 */
public final class RuntimeOnlyStartupGuard {
    private RuntimeOnlyStartupGuard() { }

    public record Admission(String service, String deploymentId, String applicationInstanceId,
            long epoch, String leaseId, String sealSha256, String validUntil) { }

    public static Admission verify(String signedSealDocument, RuntimeStartupTrustAnchor anchor,
            Map<String, DataSource> activeRuntimePools, RuntimePoolInspectionPort inspector,
            RuntimeStartupFreshnessPort freshness, Clock deploymentClock) {
        require(anchor != null && inspector != null && freshness != null && deploymentClock != null,
                "independent policy, clock, native inspector and freshness provider required");
        RuntimeStreamStartupSeal seal = RuntimeStartupSealJson.seal(signedSealDocument);
        RuntimeStartupSealSignatureVerifier.verifySeal(seal, anchor.trustedKeys());
        RuntimeStreamStartupSeal.Claims claims = seal.claims();
        verifyAnchor(claims, anchor);
        Instant initialTime;
        try {
            initialTime = deploymentClock.instant();
            require(initialTime != null, "deployment clock unavailable");
        } catch (Exception exception) {
            throw failure("deployment clock unavailable");
        }
        sealTime(claims, anchor, initialTime);
        require(activeRuntimePools != null, "runtime pool registry missing");
        Map<String, DataSource> pools = Map.copyOf(activeRuntimePools);
        Set<String> expected = claims.runtimePurposes().stream().map(RuntimeStreamStartupSeal.RuntimePurpose::qualifier)
                .collect(java.util.stream.Collectors.toSet());
        require(pools.keySet().equals(expected), "runtime registry differs; no disabled, owner, foreign or fallback pool allowed");
        Set<DataSource> instances = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        pools.values().forEach(pool -> require(pool != null && instances.add(pool), "runtime pool instance reused across bindings"));
        String sealSha256 = RuntimeStartupSealJson.sealSha256(seal);
        RuntimeStartupFreshnessPort.Reservation request = new RuntimeStartupFreshnessPort.Reservation(
                claims.service(), claims.deploymentId(), claims.applicationInstanceId(), claims.epoch(),
                claims.permitId(), claims.startupChallengeSha256(), sealSha256);
        String leaseId = null;
        boolean admitted = false;
        try {
            java.util.Optional<String> reservationResponse = freshness.reserve(request);
            Instant reservationTime = nextTime(deploymentClock, initialTime);
            sealTime(claims, anchor, reservationTime);
            RuntimeStartupLease reserved = lease(reservationResponse, anchor, request,
                    "RESERVED", null, reservationTime, claims);
            leaseId = reserved.claims().leaseId();
            for (RuntimeStreamStartupSeal.RuntimePurpose runtime : claims.runtimePurposes()) {
                RuntimePoolInspectionPort.Inspection actual = inspector.inspect(runtime, pools.get(runtime.qualifier()))
                        .orElseThrow(() -> failure("runtime native inspection unavailable"));
                verifyRuntime(runtime, actual, claims);
            }
            // Recheck the wall clock, current epoch, lease and fence AFTER the native reads. The
            // authority must atomically reject activation if Control advanced the epoch meanwhile.
            Instant activationTime = nextTime(deploymentClock, reservationTime);
            sealTime(claims, anchor, activationTime);
            require(activationTime.isBefore(instant(reserved.claims().expiresAt())), "startup reservation expired during native inspection");
            java.util.Optional<String> activationResponse = freshness.activate(
                    new RuntimeStartupFreshnessPort.Activation(request, leaseId));
            Instant activationResponseTime = nextTime(deploymentClock, activationTime);
            sealTime(claims, anchor, activationResponseTime);
            require(activationResponseTime.isBefore(instant(reserved.claims().expiresAt())),
                    "startup reservation expired during authority activation");
            RuntimeStartupLease activated = lease(activationResponse, anchor, request,
                    "ACTIVE", leaseId, activationResponseTime, claims);
            require(!instant(activated.claims().issuedAt()).isBefore(instant(reserved.claims().issuedAt())),
                    "active lease predates its reservation");
            admitted = true;
            return new Admission(claims.service(), claims.deploymentId(), claims.applicationInstanceId(),
                    claims.epoch(), leaseId, sealSha256, activated.claims().expiresAt());
        } catch (Exception exception) {
            // Never propagate provider/driver/parser messages, documents or connection-string causes.
            throw failure("signed runtime startup protocol rejected or authority unavailable");
        } finally {
            if (!admitted && leaseId != null) {
                try { freshness.release(request, leaseId); }
                catch (Exception ignored) { /* local readiness remains closed; do not attach secrets */ }
            }
        }
    }

    private static Instant nextTime(Clock clock, Instant previous) {
        Instant now = clock.instant();
        require(now != null && !now.isBefore(previous), "deployment clock regressed during startup");
        return now;
    }

    private static void verifyAnchor(RuntimeStreamStartupSeal.Claims seal, RuntimeStartupTrustAnchor anchor) {
        require(seal.service().equals(anchor.service()) && seal.topologyRevision().equals(anchor.topologyRevision())
                && seal.deploymentId().equals(anchor.deploymentId())
                && seal.applicationInstanceId().equals(anchor.applicationInstanceId())
                && seal.epoch() == anchor.expectedEpoch() && seal.keyRevision() == anchor.expectedKeyRevision()
                && seal.startupChallengeSha256().equals(anchor.startupChallengeSha256())
                && seal.controlReference().equals(anchor.expectedControlReference())
                && seal.sourceRevision().equals(anchor.expectedSourceRevision())
                && seal.sourceArtifactSha256().equals(anchor.expectedSourceArtifactSha256())
                && seal.manifestSha256().equals(anchor.expectedManifestSha256())
                && seal.controlReceiptSha256().equals(anchor.expectedControlReceiptSha256())
                && seal.streams().equals(anchor.expectedStreams())
                && seal.runtimePurposes().equals(anchor.expectedRuntimePurposes()), "signed seal differs from independent deployment policy");
    }
    private static void sealTime(RuntimeStreamStartupSeal.Claims seal, RuntimeStartupTrustAnchor anchor, Instant now) {
        Instant start = instant(seal.notBefore()); Instant end = instant(seal.expiresAt());
        require(!now.isBefore(start) && now.isBefore(end)
                && Duration.between(start, end).compareTo(anchor.maximumSealLifetime()) <= 0,
                "seal missing, expired, future or unbounded");
    }
    private static RuntimeStartupLease lease(java.util.Optional<String> document, RuntimeStartupTrustAnchor anchor,
            RuntimeStartupFreshnessPort.Reservation request, String phase, String expectedLeaseId,
            Instant now, RuntimeStreamStartupSeal.Claims seal) {
        require(document != null && document.isPresent(), "current deployment lease unavailable");
        RuntimeStartupLease response = RuntimeStartupSealJson.lease(document.orElseThrow());
        RuntimeStartupSealSignatureVerifier.verifyLease(response, anchor.trustedKeys());
        RuntimeStartupLease.Claims actual = response.claims();
        require(actual.phase().equals(phase) && actual.service().equals(request.service())
                && actual.deploymentId().equals(request.deploymentId())
                && actual.applicationInstanceId().equals(request.applicationInstanceId())
                && actual.epoch() == request.epoch() && actual.keyRevision() == anchor.expectedKeyRevision()
                && actual.permitId().equals(request.permitId())
                && actual.startupChallengeSha256().equals(request.startupChallengeSha256())
                && actual.sealSha256().equals(request.sealSha256())
                && (expectedLeaseId == null || expectedLeaseId.equals(actual.leaseId())), "fresh lease binding differs");
        Instant issued = instant(actual.issuedAt()); Instant end = instant(actual.expiresAt());
        require(!now.isBefore(issued) && now.isBefore(end) && !end.isAfter(instant(seal.expiresAt()))
                && Duration.between(issued, end).compareTo(anchor.maximumLeaseLifetime()) <= 0,
                "fresh deployment lease expired, future or unbounded");
        return response;
    }
    private static void verifyRuntime(RuntimeStreamStartupSeal.RuntimePurpose expected,
            RuntimePoolInspectionPort.Inspection actual, RuntimeStreamStartupSeal.Claims seal) {
        require(expected.principal().equals(actual.originalJdbcLogin())
                && expected.principal().equals(actual.currentUser()) && expected.principal().equals(actual.sessionUser())
                && expected.database().equals(actual.database())
                && expected.trustedEndpointId().equals(actual.trustedEndpointId())
                && expected.serverAddress().equals(actual.serverAddress()) && expected.serverPort() == actual.serverPort()
                && actual.autoCommit() && expected.readOnly() == actual.readOnly()
                && expected.searchPath().equals(actual.searchPath()) && actual.roleSettingNone()
                && actual.replicationRoleOrigin() && actual.directLogin(), "original runtime login/catalog/pool posture differs");
        require(!actual.elevatedRoleAttributes() && !actual.elevatedMembership()
                && !actual.databaseCreate() && !actual.databaseTemporary() && !actual.foreignDatabaseConnect()
                && !actual.anySchemaCreate() && !actual.anyObjectOwnership() && !actual.anyHistoryPrivilege(),
                "runtime has owner, DDL, TEMP, history, foreign or elevated authority");
        require(expected.privilegeSurfaceSha256().equals(actual.privilegeSurfaceSha256()), "runtime exact privilege surface differs");
        require(seal.streams().stream().filter(stream -> expected.streamKeys().contains(stream.streamKey()))
                .allMatch(stream -> stream.postgresVersion().equals(actual.postgresVersion())), "runtime PostgreSQL version differs");
    }
}
