package com.dwp.core.database.authority;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/** Pure projection of deployment-approved evidence. Does not register, create or open pools. */
public final class ScalarNineRuntimeRegistryAdapterV1 {
    private ScalarNineRuntimeRegistryAdapterV1() { }

    public static Selection selectService(ScalarNineRuntimeRegistryContract contract, String service,
            List<RuntimeStreamStartupSeal.StreamEvidence> externallyApprovedStreamEvidence) {
        require(contract != null, "scalar contract required");
        var catalog = contract.catalog(service);
        var authorities = contract.streams().stream().filter(s -> s.service().equals(service)).toList();
        var evidence = ordered(externallyApprovedStreamEvidence, RuntimeStreamStartupSeal.StreamEvidence::streamKey, "evidence");
        require(evidence.stream().map(RuntimeStreamStartupSeal.StreamEvidence::streamKey).toList()
                .equals(authorities.stream().map(ScalarNineRuntimeRegistryContract.Stream::streamKey).toList()),
                "service exact evidence set differs");
        for (int i = 0; i < authorities.size(); i++) {
            var expected = authorities.get(i); var actual = evidence.get(i);
            require(actual.database().equals(catalog.database()) && actual.schema().equals(expected.schema())
                    && actual.historyTable().equals(expected.historyTable())
                    && actual.migrationPrincipal().equals(expected.migrationPrincipal()), "scalar evidence identity differs");
        }
        var runtime = contract.runtimeBindings().stream().filter(r -> r.bindingKey().equals(service + "-runtime"))
                .findFirst().orElseThrow(() -> failure("missing scalar runtime binding"));
        var history = evidence.stream().map(e -> new PrivilegeSurfaceCompilerV1.HistoryRelation(
                e.streamKey(), e.database(), e.schema(), e.historyTable())).toList();
        return new Selection(service, evidence, List.of(runtime),
                new PrivilegeSurfaceCompilerV1.Policy(PrivilegeSurfaceCompilerV1.VERSION, history), List.of(runtime.qualifier()));
    }

    public static DataSource bindRegisteredPool(Selection selection, Map<String, DataSource> serviceRegistry) {
        require(selection != null && serviceRegistry != null
                && serviceRegistry.keySet().equals(java.util.Set.copyOf(selection.exactRuntimeQualifiers())),
                "registered runtime pool set differs");
        DataSource pool = serviceRegistry.get(selection.exactRuntimeQualifiers().get(0));
        require(pool != null, "registered runtime pool missing");
        return pool;
    }

    public static final class Selection {
        private final String service;
        private final List<RuntimeStreamStartupSeal.StreamEvidence> expectedStreams;
        private final List<RuntimeStreamStartupSeal.RuntimePurpose> expectedRuntimePurposes;
        private final PrivilegeSurfaceCompilerV1.Policy compilerHistoryPolicy;
        private final List<String> exactRuntimeQualifiers;

        private Selection(String service, List<RuntimeStreamStartupSeal.StreamEvidence> expectedStreams,
                List<RuntimeStreamStartupSeal.RuntimePurpose> expectedRuntimePurposes,
                PrivilegeSurfaceCompilerV1.Policy compilerHistoryPolicy, List<String> exactRuntimeQualifiers) {
            this.service = service;
            this.expectedStreams = List.copyOf(expectedStreams);
            this.expectedRuntimePurposes = List.copyOf(expectedRuntimePurposes);
            this.compilerHistoryPolicy = Objects.requireNonNull(compilerHistoryPolicy);
            this.exactRuntimeQualifiers = List.copyOf(exactRuntimeQualifiers);
        }
        public String service() { return service; }
        public List<RuntimeStreamStartupSeal.StreamEvidence> expectedStreams() { return expectedStreams; }
        public List<RuntimeStreamStartupSeal.RuntimePurpose> expectedRuntimePurposes() { return expectedRuntimePurposes; }
        public PrivilegeSurfaceCompilerV1.Policy compilerHistoryPolicy() { return compilerHistoryPolicy; }
        public List<String> exactRuntimeQualifiers() { return exactRuntimeQualifiers; }
    }
}
