package com.dwp.services.platform.widgetregistry;

import java.util.List;
import java.util.Set;

abstract class WidgetCatalogBaselineSupport {
    protected static final List<BaselineWidget> NATIVE_BASELINE = List.of(
            new BaselineWidget(
                    "core.workspace.command-rail", "1.0.1",
                    "36de53926e21ef11e61c78f6325fdf35b37998fe42403e0df0e70d71e3f4df13",
                    "home.command-rail", "core.workspace", "APP.WORK",
                    "30000000-0000-0000-0000-000000000001",
                    "31000000-0000-0000-0000-000000000101"),
            new BaselineWidget(
                    "core.workspace.daily-brief", "1.0.0",
                    "9b7f48b7ea4ef429120db330a4972c3315ad682759fa86e49c212c42bdd02406",
                    "home.daily-brief", "core.workspace", "APP.WORK",
                    "30000000-0000-0000-0000-000000000002",
                    "31000000-0000-0000-0000-000000000002"),
            new BaselineWidget(
                    "core.work.focus", "1.0.0",
                    "36d1b02326e4725a235749e173dfdf50a0423ef30f42d7ccab97946ba826d893",
                    "home.focus", "core.work", "APP.WORK",
                    "30000000-0000-0000-0000-000000000003",
                    "31000000-0000-0000-0000-000000000003"),
            new BaselineWidget(
                    "core.calendar.schedule", "1.0.0",
                    "7f3e090997a213e9d3e6f8184e1458e57382c5f31db79f00fbf678d36f884f5d",
                    "home.schedule", "core.calendar", "APP.CALENDAR",
                    "30000000-0000-0000-0000-000000000004",
                    "31000000-0000-0000-0000-000000000004"),
            new BaselineWidget(
                    "core.activity.activity", "1.0.0",
                    "fbab61015ec3b20c2faf9810b1758aebbd7517029baa64cb6b99190815836ca1",
                    "home.activity", "core.activity", "APP.ACTIVITY",
                    "30000000-0000-0000-0000-000000000005",
                    "31000000-0000-0000-0000-000000000005"),
            new BaselineWidget(
                    "core.work.focus-balance", "1.0.1",
                    "5f4c5990a0b1b417832c93074f92a00cbb8c9e4f4e49240073120c485a8c9436",
                    "home.focus-balance", "core.calendar", "APP.CALENDAR",
                    "30000000-0000-0000-0000-000000000006",
                    "31000000-0000-0000-0000-000000000106"),
            new BaselineWidget(
                    "core.calendar.meeting-load", "1.0.1",
                    "90d31f29e1dbc8e26a49475174aca5ecd76d557f3b7d39975f58ff1f047bb6c3",
                    "home.meeting-load", "core.calendar", "APP.CALENDAR",
                    "30000000-0000-0000-0000-000000000007",
                    "31000000-0000-0000-0000-000000000107"));

    protected static final Set<String> OWNER_PROVIDER_EVIDENCE = Set.of(
            "MANIFEST", "SECURITY", "PRIVACY");

    protected static final List<BaselineWidget> OWNER_PROVIDER_SHADOW_BASELINE = List.of(
            new BaselineWidget(
                    "approval.focus-queue", "1.0.0",
                    "d203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600",
                    "home.approval.focus-queue", "core.approvals", "APP.APPROVALS",
                    "36000000-0000-0000-0000-000000000001",
                    "36100000-0000-0000-0000-000000000001"),
            new BaselineWidget(
                    "approval.my-requests", "1.0.0",
                    "a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12",
                    "home.approval.my-requests", "core.approvals", "APP.APPROVALS",
                    "36000000-0000-0000-0000-000000000002",
                    "36100000-0000-0000-0000-000000000002"),
            new BaselineWidget(
                    "meetings.next-prep", "1.0.0",
                    "12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71",
                    "home.meetings.next-prep", "core.meetings", "APP.MEETINGS",
                    "36000000-0000-0000-0000-000000000003",
                    "36100000-0000-0000-0000-000000000003"),
            new BaselineWidget(
                    "meetings.followup-candidates", "1.0.0",
                    "9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b",
                    "home.meetings.followup-candidates", "core.meetings", "APP.MEETINGS",
                    "36000000-0000-0000-0000-000000000004",
                    "36100000-0000-0000-0000-000000000004"),
            new BaselineWidget(
                    "notification.app-badges", "1.0.0",
                    "99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930",
                    "home.notification.app-badges", "core.notifications", "APP.NOTIFICATIONS",
                    "36000000-0000-0000-0000-000000000005",
                    "36100000-0000-0000-0000-000000000005"),
            new BaselineWidget(
                    "notification.response-queue", "1.0.0",
                    "1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b",
                    "home.notification.response-queue", "core.notifications", "APP.NOTIFICATIONS",
                    "36000000-0000-0000-0000-000000000006",
                    "36100000-0000-0000-0000-000000000006"),
            new BaselineWidget(
                    "space.change-feed", "1.0.0",
                    "679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1",
                    "home.space.change-feed", "core.spaces", "APP.SPACES",
                    "36000000-0000-0000-0000-000000000007",
                    "36100000-0000-0000-0000-000000000007"),
            new BaselineWidget(
                    "space.response-queue", "1.0.0",
                    "cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457",
                    "home.space.response-queue", "core.spaces", "APP.SPACES",
                    "36000000-0000-0000-0000-000000000008",
                    "36100000-0000-0000-0000-000000000008"),
            new BaselineWidget(
                    "messaging.response-queue", "1.0.0",
                    "3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1",
                    "home.messaging.response-queue", "core.messaging", "APP.MESSAGING",
                    "36000000-0000-0000-0000-000000000009",
                    "36100000-0000-0000-0000-000000000009"),
            new BaselineWidget(
                    "messaging.change-feed", "1.0.0",
                    "c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc",
                    "home.messaging.change-feed", "core.messaging", "APP.MESSAGING",
                    "36000000-0000-0000-0000-000000000010",
                    "36100000-0000-0000-0000-000000000010"),
            new BaselineWidget(
                    "hr.edu", "1.0.0",
                    "05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8",
                    "home.hr.edu", "core.people", "APP.HCM",
                    "36000000-0000-0000-0000-000000000011",
                    "36100000-0000-0000-0000-000000000011"),
            new BaselineWidget(
                    "hr.team-pulse", "1.0.0",
                    "9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df",
                    "home.hr.team-pulse", "core.people", "APP.HCM",
                    "36000000-0000-0000-0000-000000000012",
                    "36100000-0000-0000-0000-000000000012"),
            new BaselineWidget(
                    "workplace.booking", "1.0.0",
                    "3da8f665137fd670411f87893af302133cea70fda9a402fce690f85686c2869e",
                    "home.workplace.booking", "core.workplace", "APP.WORKPLACE",
                    "36400000-0000-0000-0000-000000000001",
                    "36500000-0000-0000-0000-000000000001"),
            new BaselineWidget(
                    "dwaion.artifact", "1.1.0",
                    "eb2152b7f1cf21611bb4a2c1781f7f267c5cd0c6d489d4edc8f680bb4fa6af54",
                    "home.dwaion.artifact", "ai.agent-runtime", "APP.DWAION_ARTIFACTS",
                    "36400000-0000-0000-0000-000000000002",
                    "36500000-0000-0000-0000-000000000003"));

    protected record BaselineWidget(
            String definitionKey, String semanticVersion, String manifestHash, String rendererKey,
            String ownerProductKey, String sourceAppResourceKey,
            String definitionId, String versionId) {
        boolean matches(WidgetDefinition definition, WidgetDefinitionVersion version) {
            return definitionId.equals(definition.getDefinitionId().toString())
                    && versionId.equals(version.getVersionId().toString())
                    && definitionKey.equals(definition.getDefinitionKey())
                    && ownerProductKey.equals(definition.getOwnerProductKey())
                    && definition.getDefinitionId().equals(version.getDefinitionId())
                    && semanticVersion.equals(version.getSemanticVersion())
                    && manifestHash.equals(version.getManifestHash())
                    && rendererKey.equals(version.getRendererKey());
        }

        boolean matchesBinding(WidgetRendererBinding binding) {
            return "NATIVE".equals(binding.getKind())
                    && rendererKey.equals(binding.getRendererKey())
                    && ownerProductKey.equals(binding.getOwnerProductKey())
                    && sourceAppResourceKey.equals(binding.getSourceAppResourceKey())
                    && manifestHash.equals(binding.getBindingRevision());
        }
    }
}
