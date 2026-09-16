package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Exact append-only seed lineage and immutable descriptor release pins. */
final class ProductAuthorizationReleaseLineage {
    private static final Map<Long, String> CHECKSUMS = Map.ofEntries(
            Map.entry(1L, "bc34f47b0ad783d27aa7979f25f75e2fdf29506a12a23c0088f94837abad0b67"),
            Map.entry(2L, "5b634a35472ef98ecdd5ca9efe7a716020d8f3ae0d8f5025d76bbf072692c12c"),
            Map.entry(3L, "f90c4e3a734204a4619ae77d3476ebc7cc802c43ed8574fcf4f3fc85def67a8e"),
            Map.entry(4L, "a9cd08260fd9a11dd7c612f2db6f03bb312f1e7843a2eb10b4082660da151137"),
            Map.entry(5L, "c69816a06349fcbd45a0d946debfbce1d67e09b3ed87a8b056ec8a43f852109f"),
            Map.entry(6L, "7cf8602aa2da5f7a0464b23cfd84a8f381e2d3eb85333ed8a8e483865b2b0abe"),
            Map.entry(7L, "fe9721ef01164c64e03f8798f89765bdf35e55993cf98ad1f6f9c3611dd8d61a"),
            Map.entry(8L, "9449a516a2dbd96106e71963cbda764b80d83f0f61fa861d110d85517adac942"),
            Map.entry(9L, "02b19c4119e560b63d4054ec317fe7e4d694e402a5af03960c63b20db4b41ab7"),
            Map.entry(10L, "1f97638c95a192f0ec7f01053c3965f79b7a3ee4eb9781ea56e3cf8eccc6889b"),
            Map.entry(11L, "e9a32c9312feb325db1294e3c00d34a110474a48fba16399eb1fc52b39fc9043"),
            Map.entry(12L, "65155dcc88f454a0ad2530518f8ec9b0c070afd31d583a19f980dd3d10f78a74"),
            Map.entry(13L, "3bd67d7b145c5b7c845788c70f8884c8afadedd9920de419ecd1e1d0e8a4c8b0"),
            Map.entry(14L, "7ee0bac12ddfbc72dda55a5014c67b0798caa68a5ffc73b4be479d06a4590336"),
            Map.entry(15L, "5fdd43747ffb3621cd7e032bb4f44c4a761d3350a2e5b0c0d1ff47bd3a081085"),
            Map.entry(16L, "da0a7a2006df0c8a7bae78e1c7fc8ca42804ea25fa6062b947c075281ac0c4c4"));
    private static final Map<Long, Map<String, Integer>> COUNTS = Map.ofEntries(
            Map.entry(1L, counts(10, 5, 2, 6, 35)),
            Map.entry(2L, counts(34, 6, 3, 13, 76)),
            Map.entry(3L, counts(62, 14, 8, 25, 129)),
            Map.entry(4L, counts(71, 22, 16, 33, 155)),
            Map.entry(5L, counts(72, 22, 16, 33, 160)),
            Map.entry(6L, counts(119, 22, 16, 34, 250)),
            Map.entry(7L, counts(119, 22, 16, 35, 258)),
            Map.entry(8L, counts(123, 22, 16, 38, 275)),
            Map.entry(9L, counts(127, 22, 16, 41, 302)),
            Map.entry(10L, counts(132, 22, 16, 44, 318)),
            Map.entry(11L, counts(132, 22, 16, 46, 323)),
            Map.entry(12L, counts(134, 22, 16, 46, 352)),
            Map.entry(13L, counts(134, 22, 16, 46, 355)),
            Map.entry(14L, counts(134, 22, 16, 46, 360)),
            Map.entry(15L, counts(135, 22, 16, 46, 361)),
            Map.entry(16L, counts(135, 22, 16, 46, 362)));

    private ProductAuthorizationReleaseLineage() { }

    static boolean supportsDescriptorStructure(long version) {
        return CHECKSUMS.containsKey(version);
    }

    private static final Map<String, Map.Entry<String, String>> WORK_SCHEMAS = Map.of(
            "route.approvals.work.draft-command-reconciliation.data",
            Map.entry("DraftReconciliation", "5f077c0c323ef1821cbc5f3afdfd120899ef576d57099973a4f3883cb3a120cd"),
            "route.approvals.work.request-draft-revision.data",
            Map.entry("DraftRevisionDetail", "953f4fa55851c3e2ee3b4cafb406ab0a01fd96556d8cbc545f326949d8f9822a"),
            "route.approvals.work.request-draft-revisions.data",
            Map.entry("PageDraftRevision", "bccf9505e49ba2d0ad81b3ab7263751a511529764dd87e6e2e7161fe167f6ce6"),
            "route.approvals.work.requests-search.data",
            Map.entry("PageRequestSummary", "6d9c0c9fd273bee297f97d44f91b7147f3f966bc0c090c6712b2ebe62aaba46a"),
            "route.approvals.work.tasks-search.data",
            Map.entry("PageTaskSummary", "27ab4bbfb93395f73d300d67ad423f7cf5f4eb04a6b9025ab665711b6334b54a"));

    static boolean isWorkDataRoute(String routeKey) { return WORK_SCHEMAS.containsKey(routeKey); }

    static boolean matchesWorkProjection(String routeKey, String profileKey,
            ProductAuthorizationContractDtos.ResponseProjectionBinding projection) {
        Map.Entry<String, String> expected = WORK_SCHEMAS.get(routeKey);
        return expected != null && "full-work".equals(profileKey)
                && (routeKey + ".binding.01").equals(projection.apiBindingKey())
                && (routeKey + ".full-work.projection.v1").equals(projection.projectionPolicyKey())
                && expected.getKey().equals(projection.responseSchemaKey())
                && Integer.valueOf(1).equals(projection.schemaVersion())
                && expected.getValue().equals(projection.openApiSchemaSha256())
                && Boolean.FALSE.equals(projection.additionalProperties());
    }

    static void validateBundle(ProductAuthorizationContractDtos.BundleContract contract) {
        require(CHECKSUMS.containsKey(contract.version()), "Registry immutable release is not sealed.");
        require(contract.checksum().equals(CHECKSUMS.get(contract.version())),
                "Registry immutable release checksum drift.");
        require(COUNTS.get(contract.version()).equals(counts(
                        contract.capabilities().size(), contract.accessPolicies().size(),
                        contract.entitlementExpressions().size(), contract.predicatePolicies().size(),
                        contract.routes().size())),
                "Registry immutable release count drift.");
    }

    static void validateSeedIndex(ProductAuthorizationContractDtos.SeedIndex index) {
        require(index.schemaVersion() == 1, "Unsupported registry seed index schemaVersion.");
        require("product-surfaces".equals(index.bundleKey()), "Unexpected registry seed index bundleKey.");
        require("SHA-256".equals(index.indexChecksumAlgorithm()), "Only SHA-256 index checksums are supported.");
        require(index.latestVersion() == 16, "Registry latest version must be 16.");
        require(index.versions() != null && index.versions().size() == 16,
                "Registry index must contain only versions 1 through 16.");
        long expectedVersion = 1;
        Set<String> checksums = new HashSet<>();
        for (ProductAuthorizationContractDtos.SeedIndexEntry entry : index.versions()) {
            require(entry.version() == expectedVersion++, "Registry index versions must be contiguous and ordered.");
            require("DRAFT".equals(entry.bundleStatus()),
                    "Generated registry seed index may import DRAFT snapshots only.");
            require(CHECKSUMS.get(entry.version()).equals(entry.checksum())
                            && checksums.add(entry.checksum()),
                    "Registry index immutable release checksum drift.");
            require(("product-surfaces-v1.bundle-v" + entry.version() + ".json")
                            .equals(entry.artifact())
                            && ("product-surfaces-v1.bundle-v" + entry.version() + ".generated.json")
                            .equals(entry.authSeedArtifact()),
                    "Registry immutable artifact name drift.");
            require(COUNTS.get(entry.version()).equals(entry.counts()),
                    "Registry index descriptor counts are invalid.");
        }
        ProductAuthorizationContractDtos.SeedIndexEntry latest = index.versions().getLast();
        require(latest.version() == index.latestVersion()
                        && latest.checksum().equals(index.latestChecksum())
                        && latest.artifact().equals(index.latestArtifact())
                        && latest.authSeedArtifact().equals(index.latestAuthSeedArtifact()),
                "Registry latest pointer does not match the final immutable snapshot.");
    }

    private static Map<String, Integer> counts(int capabilities, int policies, int expressions,
                                               int predicates, int routes) {
        return Map.of("capabilities", capabilities, "accessPolicies", policies,
                "entitlementExpressions", expressions, "predicatePolicies", predicates, "routes", routes);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalArgumentException(message);
    }
}
