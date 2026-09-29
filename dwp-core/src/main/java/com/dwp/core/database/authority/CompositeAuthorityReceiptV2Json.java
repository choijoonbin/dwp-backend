package com.dwp.core.database.authority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/** Externally exact anchored v2 parser. No DB reads, signer, automatic adoption or runtime activation. */
public final class CompositeAuthorityReceiptV2Json {
    public static final int MAX_DOCUMENT_BYTES = 262_144;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    private CompositeAuthorityReceiptV2Json() { }

    private static CompositeAuthorityReceiptV2 receipt(String document, ExpectedBinding externalDeploymentBinding) {
        require(externalDeploymentBinding != null, "composite external deployment binding required");
        require(document != null && !document.isEmpty() && document.length() <= MAX_DOCUMENT_BYTES
                && document.getBytes(StandardCharsets.UTF_8).length <= MAX_DOCUMENT_BYTES, "composite document missing or oversized");
        CompositeAuthorityReceiptV2 parsed;
        try {
            parsed = JSON.readValue(document, CompositeAuthorityReceiptV2.class);
            require(parsed != null && canonical(parsed).equals(document), "composite document not a byte canonical object");
        } catch (Exception exception) { throw failure("strict composite v2 parse rejected"); }
        require(parsed.topologyRevision().equals(externalDeploymentBinding.topologyRevision())
                && parsed.manifestSha256().equals(externalDeploymentBinding.manifestSha256())
                && parsed.controlReference().equals(externalDeploymentBinding.currentControlReference())
                && parsed.previousCompositeReceiptSha256().equals(externalDeploymentBinding.previousReceiptSha256())
                && parsed.receiptSha256().equals(externalDeploymentBinding.receiptSha256())
                && parsed.streams().equals(externalDeploymentBinding.expectedEnabledStreams()), "composite independent binding differs");
        require(receiptDigest(parsed).equals(parsed.receiptSha256()), "composite canonical digest differs");
        return parsed;
    }

    public static CompositeAuthorityReceiptV2 scalarReceipt(String document, ScalarNineRuntimeRegistryContract approvedRegistry,
            ExpectedBinding externalDeploymentBinding) {
        require(approvedRegistry != null && externalDeploymentBinding != null
                && ScalarNineRuntimeRegistryContract.TOPOLOGY.equals(externalDeploymentBinding.topologyRevision())
                && approvedRegistry.controlReference().equals(externalDeploymentBinding.currentControlReference()), "scalar receipt topology differs");
        var parsed = receipt(document, externalDeploymentBinding);
        require(parsed.streams().stream().map(CompositeAuthorityReceiptV2.StreamSeal::streamKey).toList()
                .equals(approvedRegistry.streams().stream().map(ScalarNineRuntimeRegistryContract.Stream::streamKey).toList()),
                "scalar receipt exact nine streams differ");
        for (int i = 0; i < parsed.streams().size(); i++) {
            var actual = parsed.streams().get(i); var authority = approvedRegistry.streams().get(i);
            require(actual.database().equals(approvedRegistry.catalog(authority.service()).database())
                    && actual.migrationPrincipal().equals(authority.migrationPrincipal())
                    && actual.authoritySha256().equals(scalarAuthorityDigest(authority)), "scalar receipt authority differs");
        }
        return parsed;
    }

    public static CompositeAuthorityReceiptV2 supplementalReceipt(String document, StreamAuthorityContract approvedManifest,
            ExpectedBinding externalDeploymentBinding) {
        require(approvedManifest != null && externalDeploymentBinding != null
                && ExactStreamTopology.VERSION.equals(externalDeploymentBinding.topologyRevision())
                && StreamAuthorityJson.manifestDigest(approvedManifest).equals(approvedManifest.manifestSha256())
                && approvedManifest.manifestSha256().equals(externalDeploymentBinding.manifestSha256())
                && approvedManifest.controlReference().equals(externalDeploymentBinding.currentControlReference()), "supplemental manifest unsealed or differs");
        var parsed = receipt(document, externalDeploymentBinding);
        require(parsed.streams().stream().map(CompositeAuthorityReceiptV2.StreamSeal::streamKey).toList()
                .equals(approvedManifest.streams().stream().filter(StreamAuthorityContract.StreamAuthority::enabled)
                        .map(StreamAuthorityContract.StreamAuthority::streamKey).toList()), "enabled supplemental exact streams differ");
        for (var actual : parsed.streams()) {
            var authority = approvedManifest.stream(actual.streamKey());
            require(actual.database().equals(approvedManifest.catalog(authority.catalogKey()).database())
                    && actual.migrationPrincipal().equals(authority.migrationPrincipal())
                    && actual.authoritySha256().equals(StreamAuthorityJson.authorityDigest(authority)), "supplemental authority differs");
        }
        return parsed;
    }

    public record ExpectedBinding(String topologyRevision, String manifestSha256, String currentControlReference,
            String previousReceiptSha256, String receiptSha256, List<CompositeAuthorityReceiptV2.StreamSeal> expectedEnabledStreams) {
        public ExpectedBinding {
            require(ScalarNineRuntimeRegistryContract.TOPOLOGY.equals(topologyRevision) || ExactStreamTopology.VERSION.equals(topologyRevision),
                    "composite expected topology invalid");
            digest(manifestSha256); controlReference(currentControlReference); digest(receiptSha256);
            CompositeAuthorityReceiptV2.optionalDigest(previousReceiptSha256);
            expectedEnabledStreams = ordered(expectedEnabledStreams, CompositeAuthorityReceiptV2.StreamSeal::streamKey, "external enabled streams");
            require(!expectedEnabledStreams.isEmpty(), "external enabled streams missing");
            var keys = expectedEnabledStreams.stream().map(CompositeAuthorityReceiptV2.StreamSeal::streamKey)
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> primary = new java.util.HashSet<>();
            ScalarNineRuntimeRegistryContract.SERVICES.forEach(service -> primary.add(service + "-main"));
            primary.add("people-performance");
            require(keys.containsAll(primary) && (!ScalarNineRuntimeRegistryContract.TOPOLOGY.equals(topologyRevision)
                    || keys.equals(primary)), "composite external exact base nine scope differs");
        }
    }

    public static String canonical(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception exception) { throw failure("composite serialization rejected"); }
    }
    public static String receiptDigest(CompositeAuthorityReceiptV2 value) {
        require(value != null, "composite receipt required");
        ObjectNode node = JSON.valueToTree(value); node.remove("receiptSha256");
        return sha256("migration-control-composite-receipt-v2\n" + canonical(node));
    }
    public static String scalarAuthorityDigest(ScalarNineRuntimeRegistryContract.Stream value) {
        require(value != null, "scalar authority required");
        return sha256("scalar-nine-stream-authority-v1\n" + canonical(value));
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw failure("composite digest unavailable"); }
    }
}
