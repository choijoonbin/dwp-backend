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
            Map.entry(15L, "a9eab001b26d6488de0f7f176fa1d70fea2c9792a230769f645eb53ba90cf89f"),
            Map.entry(16L, "a677d2ad662d8791a09cdf4ad592f3483aa3dbf068ed2785c648351e8f2a20d0"),
            Map.entry(17L, "cdd1e671c8df1bbd63cab442acf175ee22c645cac071b79a3598f498c9947239"),
            Map.entry(18L, "ce5ce0e2058ab33998c0bf19ace2ff54ace06c8c0bda4dc4dca3554b06926fc5"),
            Map.entry(19L, "32bec6d3fb912af6072139aec72228281597c5b4fbb15f8b4af947c20721644c"),
            Map.entry(20L, "1acbce34c450c650aa3e8f1c11995b831f177ee4a6139dd8217ea5bdd22e7a60"),
            Map.entry(21L, "4cd1732df91d197cc47fca94699b0fb702ab1f6f2c557d3d17ce0e069d65af85"),
            Map.entry(22L, "1629b75f62c7bb524dc70faaecac73499b9f9b0fb126ab38221b6e4773f35ede"),
            Map.entry(23L, "4687f384ce79faacf4e2b1eb9c6b8eca3f2109dd62a88ac01c5deda22e1925c7"),
            Map.entry(24L, "be3db891d27cd0b94aa88ac706d9bc87d4b991c9f9d8e505e26b296647728b84"),
            Map.entry(25L, "805fa41852b1679d3c7c473d650020f481ace0c2a7910271872d935c223287c1"),
            Map.entry(26L, "b0e355e169233abef1cbcbc39672ec9a66feaa602b8cf8ab6fe4e8b3b97cd278"),
            Map.entry(27L, "f88e17c65ef95f8a9e1879b48eb093db40eda18b5fc3e077bce9b8abdfe44f6f"),
            Map.entry(28L, "7e807a353f281a9e0bc6d9d33074c5b1806946d21399bc541682bbf6dcab6f90"),
            Map.entry(29L, "aac6b6dfc94b4f4ea2956862ff1aa73ccd3459b29548fb20b3cbc26ae29cdbd8"),
            Map.entry(30L, "7c437bd768225db7dfe1c2491bcdb6ff256a2376946bab6a4ba4a62a7f31ce80"),
            Map.entry(31L, "be4e1b6db3d3f0b5100182a3c80066a39c64479f9ba88d908fee661efd3335b8"),
            Map.entry(32L, "b620ea86a8310cf23796e3e380b74c39764bdca28f41033496d21887a89da9cc"),
            Map.entry(33L, "9c9a18b44eb83de0e98f4ec16e44c1df0ce216e00bc7075462f4e35f7fb87639"),
            Map.entry(34L, "852d20e1e639e1a7170f02b5714d21d8c51a9eb8ff5ac32d8b7940b82d6be83b"),
            Map.entry(35L, "38ef33070a33b5da9a7825c764bd24daacd24c8ca4008f3a926727321d3d098d"),
            Map.entry(36L, "12e4b9329ab53ab1efd6c5f6e23ccc7869c4184a79e5a64f614fe9c40a9e704b"),
            Map.entry(37L, "1f23280116bc5b29f1a7ea6cc4ccbd2092a91673979d6bb46164a0dbb95ae10c"),
            Map.entry(38L, "a41145d6a2faefd2e6030a2ca9a070769019d4db5513ea214bf542dda31c374a"),
            Map.entry(39L, "86e796c95e45c02892d4c4ae76e7a5b36114ab09554ffc1be3a450066fe8e34c"),
            Map.entry(40L, "4dca145cbc9169c2d1fde71771b83a1a0635679a7190a39d0a21b4e76bbb9329"),
            Map.entry(41L, "026157acf598ebabf156b40e3c084c40d52346ebfd2019e2476f30ecdd2b8d93"),
            Map.entry(42L, "f73f9a64816ac8f5567a2a7c292821f4a40e6d675915ebc3c3ac8aad7d1fd983"),
            Map.entry(43L, "2222319502c362caf247a7a47fdacfc63c774dbf156f6272cafae797627e2400"),
            Map.entry(44L, "8b4e05e5974a8a25fb9edf318bb3cd609105f2e6353677b69c2eab1cd7038a1b"),
            Map.entry(45L, "6f41e17159104cd0fd721a13877611b6225bd6458cb2831f0f30e51573992e64"),
            Map.entry(46L, "3499e37653587feed433f84a79fbe7d0e80a1a7c0634d858a13f4e09d4bd015e"),
            Map.entry(47L, "925e8bfc406bc7e4ee62aee08582fdd7c67c629ffde470051704eaec5404800e"),
            Map.entry(48L, "9fd324cd67ede1f02cc8ac33c08ad880ec6e283f99f5d7aa50e0e514545aa043"),
            Map.entry(49L, "5c1dccdc27d8b260fbf001de5695e983a7107072824a8087038340d0d84cbc60"),
            Map.entry(50L, "c52222caad2749c4048c694f6582e157f6ab1184f73237f0944c3d88807a2760"),
            Map.entry(51L, "3beda5c13a3af0e265ace4533a3afcee3691c2b7486465dcf46156e58ae5ed2c"),
            Map.entry(52L, "b33fbf968e71f97b09fa497fdfad7d2f6854199170b79fd1602a0ea604977346"),
            Map.entry(53L, "c655a850ae2ac01466edb37f2e1c69f9e269dd2a4a529c3a87b1610c0561a359"),
            Map.entry(54L, "1a199b3ec84b6d6c16aaec57c9be0b09092c5f81e0c5566f893c4dbe0e0deed4"),
            Map.entry(55L, "7fb2121a1209b87234bcb389a9bd4c4addca3c4e8c8776a9aac70d278c74d4e6"),
            Map.entry(56L, "4c1e82c4ac70f7bbc4aba7a41d77b8fd238da0478501cbdfed97ebd4b5dd0930"),
            Map.entry(57L, "f7d6e2ef92f563747ed062e496638ea83498a33414fc12586c794f517e626d6e"));
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
            Map.entry(15L, counts(136, 22, 16, 46, 411)),
            Map.entry(16L, counts(137, 22, 16, 46, 425)),
            Map.entry(17L, counts(139, 22, 16, 46, 446)),
            Map.entry(18L, counts(139, 22, 16, 46, 459)),
            Map.entry(19L, counts(139, 22, 16, 46, 460)),
            Map.entry(20L, counts(159, 22, 16, 46, 686)),
            Map.entry(21L, counts(159, 22, 16, 46, 709)),
            Map.entry(22L, counts(160, 22, 16, 46, 749)),
            Map.entry(23L, counts(160, 22, 16, 46, 768)),
            Map.entry(24L, counts(160, 22, 16, 46, 776)),
            Map.entry(25L, counts(160, 22, 16, 46, 777)),
            Map.entry(26L, counts(161, 22, 16, 46, 778)),
            Map.entry(27L, counts(161, 22, 16, 46, 779)),
            Map.entry(28L, counts(161, 22, 16, 46, 780)),
            Map.entry(29L, counts(183, 22, 16, 47, 827)),
            Map.entry(30L, counts(205, 22, 16, 47, 888)),
            Map.entry(31L, counts(205, 22, 16, 47, 911)),
            Map.entry(32L, counts(205, 22, 16, 48, 912)),
            Map.entry(33L, counts(219, 24, 17, 54, 945)),
            Map.entry(34L, counts(223, 24, 17, 54, 952)),
            Map.entry(35L, counts(236, 24, 17, 57, 974)),
            Map.entry(36L, counts(237, 24, 17, 57, 974)),
            Map.entry(37L, counts(237, 24, 17, 57, 974)),
            Map.entry(38L, counts(242, 24, 17, 59, 991)),
            Map.entry(39L, counts(245, 24, 17, 60, 1003)),
            Map.entry(40L, counts(248, 24, 17, 61, 1012)),
            Map.entry(41L, counts(252, 24, 17, 64, 1017)),
            Map.entry(42L, counts(255, 24, 17, 69, 1028)),
            Map.entry(43L, counts(256, 24, 17, 71, 1032)),
            Map.entry(44L, counts(258, 24, 17, 73, 1034)),
            Map.entry(45L, counts(258, 24, 17, 73, 1046)),
            Map.entry(46L, counts(258, 24, 17, 73, 1048)),
            Map.entry(47L, counts(258, 24, 17, 73, 1053)),
            Map.entry(48L, counts(258, 24, 17, 73, 1063)),
            Map.entry(49L, counts(262, 24, 17, 81, 1079)),
            Map.entry(50L, counts(262, 24, 17, 81, 1079)),
            Map.entry(51L, counts(265, 24, 17, 81, 1087)),
            Map.entry(52L, counts(268, 24, 17, 81, 1092)),
            Map.entry(53L, counts(269, 24, 17, 81, 1095)),
            Map.entry(54L, counts(270, 24, 17, 82, 1100)),
            Map.entry(55L, counts(270, 24, 17, 82, 1104)),
            Map.entry(56L, counts(270, 24, 17, 82, 1120)),
            Map.entry(57L, counts(271, 24, 17, 83, 1123)));

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
        require(index.latestVersion() == 57, "Registry latest version must be 57.");
        require(index.versions() != null && index.versions().size() == 57,
                "Registry index must contain only versions 1 through 57.");
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
