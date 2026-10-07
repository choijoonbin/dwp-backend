package com.dwp.core.database.authority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.dwp.core.database.authority.StreamAuthorityContract.*;

/** Strict bounded byte-canonical parser. Expected digests must come from outside these documents. */
public final class StreamAuthorityJson {
    public static final int MAX_DOCUMENT_BYTES = 262_144;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    private StreamAuthorityJson() { }

    public static StreamAuthorityContract manifest(String document, String externalManifestSha256,
                                                   String currentControlReference) {
        digest(externalManifestSha256, false); reference(currentControlReference);
        StreamAuthorityContract manifest = parse(document, StreamAuthorityContract.class);
        require(manifest.manifestSha256().equals(externalManifestSha256)
                && manifest.controlReference().equals(currentControlReference), "manifest external binding differs");
        require(manifestDigest(manifest).equals(manifest.manifestSha256()), "manifest canonical digest invalid");
        return manifest;
    }

    public static CompositeAuthorityReceipt receipt(String document, StreamAuthorityContract manifest,
            String externalReceiptSha256, String currentControlReference, String externalPreviousReceiptSha256) {
        digest(externalReceiptSha256, false); reference(currentControlReference);
        digest(externalPreviousReceiptSha256, true);
        require(manifest != null && manifestDigest(manifest).equals(manifest.manifestSha256())
                && manifest.controlReference().equals(currentControlReference), "manifest is not current and sealed");
        CompositeAuthorityReceipt receipt = parse(document, CompositeAuthorityReceipt.class);
        require(receipt.manifestSha256().equals(manifest.manifestSha256())
                && receipt.controlReference().equals(currentControlReference)
                && receipt.receiptSha256().equals(externalReceiptSha256)
                && receipt.previousCompositeReceiptSha256().equals(externalPreviousReceiptSha256),
                "composite receipt external binding differs");
        require(receiptDigest(receipt).equals(receipt.receiptSha256()), "composite receipt canonical digest invalid");
        require(receipt.streams().stream().map(CompositeAuthorityReceipt.StreamSeal::streamKey).toList()
                .equals(manifest.streams().stream().filter(StreamAuthorityContract.StreamAuthority::enabled)
                        .map(StreamAuthorityContract.StreamAuthority::streamKey).toList()),
                "receipt enabled stream set differs from manifest");
        for (CompositeAuthorityReceipt.StreamSeal seal : receipt.streams()) {
            StreamAuthority stream = manifest.stream(seal.streamKey());
            require(seal.database().equals(manifest.catalog(stream.catalogKey()).database())
                    && seal.migrationPrincipal().equals(stream.migrationPrincipal())
                    && seal.authoritySha256().equals(authorityDigest(stream)), "stream authority receipt binding differs");
        }
        return receipt;
    }

    public static String canonical(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception exception) { throw invalid("cannot serialize contract"); }
    }
    public static String manifestDigest(StreamAuthorityContract value) {
        return digestWithout("stream-authority-manifest-v1", value, "manifestSha256");
    }
    public static String receiptDigest(CompositeAuthorityReceipt value) {
        return digestWithout("migration-control-composite-receipt-v1", value, "receiptSha256");
    }
    public static String authorityDigest(StreamAuthority value) {
        return sha256("stream-authority-v1\n" + canonical(value));
    }
    private static String digestWithout(String namespace, Object value, String excluded) {
        ObjectNode object = JSON.valueToTree(value);
        object.remove(excluded);
        return sha256(namespace + "\n" + canonical(object));
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static <T> T parse(String document, Class<T> type) {
        require(document != null && !document.isEmpty()
                && document.length() <= MAX_DOCUMENT_BYTES
                && document.getBytes(StandardCharsets.UTF_8).length <= MAX_DOCUMENT_BYTES,
                "document missing or oversized");
        try {
            JsonNode tree = JSON.readTree(document);
            require(tree != null && tree.isObject(), "document must be an object");
            T value = JSON.treeToValue(tree, type);
            require(canonical(value).equals(document), "document is not byte canonical");
            return value;
        } catch (Exception exception) {
            // Input documents may accidentally contain credentials. Never echo input or parser causes.
            throw invalid("strict contract parse rejected");
        }
    }
}
