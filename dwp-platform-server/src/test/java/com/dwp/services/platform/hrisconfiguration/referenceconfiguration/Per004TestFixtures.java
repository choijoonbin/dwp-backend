package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.*;

final class Per004TestFixtures {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-19T08:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
    static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    static final String SCHEMA = "dwp.hris.performance.policy.v1";
    static final String PER_REFERENCE = "ref_per_artifact_00000000-0000-0000-0000-000000000042";
    static final String SCOPE = "a".repeat(64), PAYLOAD = "b".repeat(64), EVIDENCE = "c".repeat(64);
    static final UUID SCOPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    static AccessContext access(long tenant, long subject) {
        return new AccessContext(tenant, subject, UUID.nameUUIDFromBytes(("subject-" + subject).getBytes()),
                "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE,ACTION.HRIS_POLICY_PUBLISH:PUBLISH", "TENANT_ADMIN",
                "psc-" + "c".repeat(64), "hcm-scope-" + "d".repeat(40),
                new PopulationScope(ScopeType.LEGAL_ENTITY, SCOPE_ID, SCOPE, "decision-revision-7"),
                4_663_670_355_146_917_040L, 1_991_387_878_219_104_048L,
                NOW.plusDays(1), Set.of(), Set.of(), "HRIS_SYSTEM_ADMIN");
    }
    static CommandContext command(String verb, String key, Long aggregate) {
        boolean high = verb.equals("approve") || verb.equals("publish");
        return new CommandContext(key, UUID.nameUUIDFromBytes(key.getBytes()), "HRIS_SYSTEM_ADMIN", aggregate,
                high ? "decision-revision-7" : null, high ? "signed-step-up" : null,
                "route.hcm.configuration.configuration-version-" + verb + ".action",
                "psc-" + "c".repeat(64), "hcm-scope-" + "d".repeat(40), "decision-revision-7");
    }
    static CreateVersionCommand typed() {
        return new CreateVersionCommand(Domain.PER, NOW, null, SCHEMA, 1, null,
                MAPPER.createObjectNode().put("syntheticSecret", "transit-only-감사비저장"));
    }
    static FeatureDecision active(FeatureRequest request) {
        return new FeatureDecision(request, FEATURE, FEATURE_ROUTE, "ACTIVE", "ALLOWED", "revision-1",
                NOW.minusSeconds(1), NOW.plusSeconds(30));
    }
    static OwnerReceipt owner(ReceiptKind kind, OwnerTuple tuple, UUID id, UUID prior) {
        return new OwnerReceipt(kind, tuple, "dwp-people-server", id, prior, EVIDENCE, "SUCCEEDED",
                NOW, NOW.plusMinutes(15), kind == ReceiptKind.SIMULATION ? "d".repeat(64) : null);
    }
    private Per004TestFixtures() { }
}
