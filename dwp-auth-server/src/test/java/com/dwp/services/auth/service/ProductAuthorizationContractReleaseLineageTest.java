package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationContractReleaseLineageTest
        extends ProductAuthorizationContractTestSupport {

    @Test
    void validatesOrderedSeedIndexAndStrictSnapshotSupersets() throws IOException {
        JsonNode indexDocument = generatedDocument("product-surfaces-v1.index.generated.json");
        ProductAuthorizationContractDtos.SeedIndex index =
                validator.validateSeedIndexDocument(indexDocument);
        ProductAuthorizationContractDtos.BundleContract versionOne = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v1.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwo = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v2.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionThree = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v3.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionFour = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v4.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionFive = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v5.generated.json"));

        ProductAuthorizationContractDtos.BundleContract versionSix = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v6.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionSeven = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v7.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionEight = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v8.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionNine = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v9.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTen = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v10.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionEleven = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v11.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwelve = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v12.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionThirteen = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v13.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionFourteen = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v14.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionFifteen = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v15.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionSixteen = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v16.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionSeventeen = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v17.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionEighteen = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v18.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionNineteen = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v19.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwenty = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v20.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwentyOne = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v21.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwentyTwo = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v22.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwentyThree = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v23.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwentyFour = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v24.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwentyFive = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v25.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwentySix = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v26.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwentySeven = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v27.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwentyEight = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v28.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwentyNine = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v29.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionThirty = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v30.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionThirtyOne = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v31.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionThirtyTwo = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v32.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionThirtyThree = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v33.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionThirtyFour = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v34.generated.json"));
        assertThat(index.latestVersion()).isEqualTo(34);
        assertThat(index.latestChecksum()).isEqualTo(versionThirtyFour.checksum());
        assertThat(index.versions())
                .extracting(ProductAuthorizationContractDtos.SeedIndexEntry::version)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L,
                        12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L, 21L, 22L, 23L, 24L, 25L,
                        26L, 27L, 28L, 29L, 30L, 31L, 32L, 33L, 34L);
        assertThat(index.versions())
                .extracting(ProductAuthorizationContractDtos.SeedIndexEntry::bundleStatus)
                .containsOnly("DRAFT");
        assertStrictCapabilitySuperset(versionOne, versionTwo);
        assertStrictCapabilitySuperset(versionTwo, versionThree);
        assertStrictCapabilitySuperset(versionThree, versionFour);
        assertStrictCapabilitySuperset(versionFour, versionFive);
        assertStrictCapabilitySuperset(versionFive, versionSix);
        assertThat(versionSeven.capabilities()).extracting(
                ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsExactlyElementsOf(versionSix.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey).toList());
        assertThat(versionSeven.routes()).hasSize(258);
        assertStrictCapabilitySuperset(versionSeven, versionEight);
        assertThat(versionEight.routes()).hasSize(275);
        assertStrictCapabilitySuperset(versionEight, versionNine);
        assertStrictCapabilitySuperset(versionNine, versionTen);
        assertThat(versionNine.routes()).hasSize(302);
        assertThat(versionTen.routes()).hasSize(318);
        assertThat(versionEleven.routes()).hasSize(323)
                .containsAll(versionTen.routes());
        assertThat(versionEleven.capabilities())
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsExactlyElementsOf(versionTen.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                        .toList());
        assertStrictCapabilitySuperset(versionEleven, versionTwelve);
        assertThat(versionTwelve.routes()).hasSize(352)
                .containsAll(versionEleven.routes());
        assertThat(versionThirteen.capabilities())
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsExactlyElementsOf(versionTwelve.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                        .toList());
        assertThat(versionThirteen.routes()).hasSize(355)
                .containsAll(versionTwelve.routes());
        assertThat(versionFourteen.capabilities())
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsExactlyElementsOf(versionThirteen.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                        .toList());
        assertThat(versionFourteen.routes()).hasSize(360)
                .containsAll(versionThirteen.routes());
        assertStrictCapabilitySuperset(versionFourteen, versionFifteen);
        assertThat(versionFifteen.capabilities()).hasSize(136);
        assertThat(versionFifteen.routes()).hasSize(411)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionFourteen.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList());
        assertThat(versionFifteen.routes())
                .filteredOn(route -> "route.workplace.work.explore.page"
                        .equals(route.routeContractKey()))
                .singleElement()
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::authorizationEquivalenceKey)
                .isEqualTo("wire-authority.workplace.work.explore.v1");
        assertStrictCapabilitySuperset(versionFifteen, versionSixteen);
        assertThat(versionSixteen.capabilities()).hasSize(137);
        assertThat(versionSixteen.routes()).hasSize(425)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionFifteen.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains("route.workplace.work.booking-intent-preview.action",
                        "route.workplace.work.booking-batch-compensation.action",
                        "route.workplace.work.waitlist-cancel.action");
        assertThat(versionEighteen.routes()).hasSize(459)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionSeventeen.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains(
                        "route.workplace.work.service-order-events.data",
                        "route.workplace.work.service-order-line-cancel.action",
                        "route.workplace.management.service-fulfillment-attachment-scan-status.data",
                        "route.workplace.management.service-fulfillment-line-adjustment-reconcile.action");
        assertThat(versionNineteen.routes()).hasSize(460)
                .containsAll(versionEighteen.routes());
        assertThat(versionTwenty.capabilities()).hasSize(159);
        assertThat(versionTwenty.routes()).hasSize(686)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionNineteen.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains(
                        "route.workplace.work.home.page",
                        "route.workplace.work.wayfinding.page",
                        "route.workplace.management.safety.page",
                        "route.workplace.management.visits.page",
                        "route.workplace.management.visit-policies.page",
                        "route.workplace.management.access-zones.page",
                        "route.workplace.management.visit-providers.page",
                        "route.workplace.management.kiosk-devices.page",
                        "route.workplace.management.overview.page",
                        "route.workplace.management.operations.page",
                        "route.workplace.management.locations.page",
                        "route.workplace.management.policy.page",
                        "route.workplace.management.room-operations.page",
                        "route.workplace.management.room-policy.page");
        assertThat(versionTwentyOne.capabilities()).hasSize(159);
        assertThat(versionTwentyOne.routes()).hasSize(709)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionTwenty.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains(
                        "route.dwaion.management.ai-control.page",
                        "route.dwaion.management.ai-control-bootstrap.action",
                        "route.dwaion.management.ai-control-update.action",
                        "route.dwaion.management.ai-control-emergency.action");
        assertStrictCapabilitySuperset(versionTwentyOne, versionTwentyTwo);
        assertThat(versionTwentyTwo.capabilities()).hasSize(160);
        assertThat(versionTwentyTwo.routes()).hasSize(749)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionTwentyOne.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains(
                        "route.workplace.work.resource-favorites-get.data",
                        "route.workplace.work.resource-favorite-set.action",
                        "route.workplace.work.booking-intent-holds-release.action",
                        "route.dwaion.management.control-plane-command.action");
        assertThat(versionTwentyThree.capabilities())
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsExactlyElementsOf(versionTwentyTwo.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                        .toList());
        assertThat(versionTwentyThree.routes()).hasSize(768)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionTwentyTwo.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains(
                        "route.workplace.work.resource-command-context.data",
                        "route.workplace.work.resource-command-execute.action",
                        "route.workplace.management.space-planning-report-execute.action",
                        "route.workplace.work.safety-incidents-by-incident-id-emergency-contacts-get.data",
                        "route.workplace.management.safety-incidents-by-incident-id-emergency-handoffs-by-command-id-reconcile-post.action");
        assertThat(versionTwentyFour.capabilities())
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsExactlyElementsOf(versionTwentyThree.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                        .toList());
        assertThat(versionTwentyFour.routes()).hasSize(776)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionTwentyThree.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains(
                        "route.dwaion.work.research-raw-download.data",
                        "route.dwaion.work.routine-webhook-trigger.action",
                        "route.dwaion.work.artifact-collaboration-comments.action");
        assertThat(versionTwentyFive.capabilities())
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsExactlyElementsOf(versionTwentyFour.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                        .toList());
        assertThat(versionTwentyFive.routes()).hasSize(777)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionTwentyFour.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains("route.dwaion.work.artifact-collaboration-review-decision.action");
        assertStrictCapabilitySuperset(versionTwentyFive, versionTwentySix);
        assertThat(versionTwentySix.routes()).hasSize(778)
                .containsAll(versionTwentyFive.routes())
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .contains("route.workplace.work.home-read-model.data");
        assertThat(versionTwentySeven.capabilities())
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsExactlyElementsOf(versionTwentySix.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                        .toList());
        assertThat(versionTwentySeven.routes()).hasSize(779)
                .containsAll(versionTwentySix.routes())
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .contains("route.workplace.work.home-widget-action-execute.action");
        assertThat(versionTwentyEight.capabilities())
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsExactlyElementsOf(versionTwentySeven.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                        .toList());
        assertThat(versionTwentyEight.capabilities())
                .filteredOn(value -> "workplace.home.read".equals(value.contractKey()))
                .singleElement()
                .satisfies(value -> assertThat(value.routeContractKeys())
                        .contains("route.workplace.work.home-shadow-receipt.action"));
        assertThat(versionTwentyEight.routes()).hasSize(780)
                .containsAll(versionTwentySeven.routes())
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .contains("route.workplace.work.home-shadow-receipt.action");
        assertStrictCapabilitySuperset(versionTwentyEight, versionTwentyNine);
        assertThat(versionTwentyNine.capabilities()).hasSize(183);
        assertThat(versionTwentyNine.routes()).hasSize(827)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionTwentyEight.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains(
                        "route.mail.work.message-advanced-send.action",
                        "route.mail.work.proposal-decision.action",
                        "route.admin.mail.retention.purge-execute.action");
        assertStrictCapabilitySuperset(versionTwentyNine, versionThirty);
        assertThat(versionThirty.capabilities()).hasSize(205)
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .contains(
                        "dwaion.work.routines.approve",
                        "mail.work.address-book.manage",
                        "mail.work.delivery.manage",
                        "mail.work.folder.manage",
                        "mail.work.rule.manage");
        assertThat(versionThirty.routes()).hasSize(888)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionTwentyNine.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains(
                        "route.dwaion.work.proposal-handoff-draft.action",
                        "route.dwaion.work.research-pdf-download.data",
                        "route.dwaion.work.research-recovery.action",
                        "route.dwaion.work.routine-advanced.action",
                        "route.dwaion.work.routine-advanced-pending-approvals.data",
                        "route.dwaion.work.artifact-collaboration-remediation.action",
                        "route.dwaion.work.personal-deletion-evidence.action",
                        "route.mail.work.folder-create.action",
                        "route.mail.work.rule-order.action",
                        "route.admin.mail.connection-test-send.action");
        assertThat(versionThirtyOne.capabilities())
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsExactlyElementsOf(versionThirty.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                        .toList());
        assertThat(versionThirtyOne.routes()).hasSize(911)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionThirty.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains(
                        "route.approvals.admin.audit-export-verify.action",
                        "route.approvals.admin.deployment-canary-control.action",
                        "route.approvals.admin.form-studio-field-update.action",
                        "route.approvals.admin.policy-governance-publish.action",
                        "route.approvals.admin.workflow-studio-retire.action",
                        "route.approvals.work.workflow-template.data");
        assertThat(versionThirtyTwo.capabilities())
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsExactlyElementsOf(versionThirtyOne.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                        .toList());
        assertThat(versionThirtyTwo.routes()).hasSize(912)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionThirtyOne.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains("route.communications.management.code-sets.data");
        assertThat(versionThirtyThree.capabilities()).hasSize(219)
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsAll(versionThirtyTwo.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                        .toList())
                .contains(
                        "hcm.operations.talent.approve",
                        "hcm.operations.payroll-foundation.publish",
                        "hcm.time.work-regime.publish");
        assertThat(versionThirtyThree.routes()).hasSize(945)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionThirtyTwo.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains(
                        "route.communications.management.code-sets.data",
                        "route.hcm.operations.people360-search.data",
                        "route.hcm.operations.performance-cycle-publish.action",
                        "route.hcm.operations.payroll-foundation-publish.action",
                        "route.hcm.operations.work-plan-publish.action",
                        "route.hcm.management.system.page");
        assertThat(versionThirtyFour.capabilities()).hasSize(223)
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .containsAll(versionThirtyThree.capabilities().stream()
                        .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                        .toList())
                .contains(
                        "hcm.operations.assignment-proposal.create",
                        "hcm.operations.assignment-proposal.validate",
                        "hcm.operations.assignment-proposal.submit",
                        "hcm.operations.assignment-proposal.cancel");
        assertThat(versionThirtyFour.routes()).hasSize(952)
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .containsAll(versionThirtyThree.routes().stream()
                        .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                        .toList())
                .contains(
                        "route.hcm.operations.assignment-detail.data",
                        "route.hcm.operations.assignment-timeline.data",
                        "route.hcm.operations.assignment-proposal-detail.data",
                        "route.hcm.operations.assignment-proposal-create.action",
                        "route.hcm.operations.assignment-proposal-validate.action",
                        "route.hcm.operations.assignment-proposal-submit.action",
                        "route.hcm.operations.assignment-proposal-cancel.action");
        assertThat(versionSix.routes()).filteredOn(value -> value.routeContractKey().equals(
                "route.services.work.request-information-response.action"))
                .singleElement().satisfies(route -> {
                    assertThat(route.routeKind()).isEqualTo("ACTION");
                    assertThat(route.accessProfiles().getFirst().requiredAccess().capabilityContractKey())
                            .isEqualTo("services.request.respond");
                });
        assertThat(versionOne.capabilities())
                .filteredOn(value -> "REQUIRED".equals(value.responsibilityRequirement()))
                .allMatch(value -> "APP_CONFIG_ADMIN".equals(
                        value.requiredResponsibilityCode()));
        assertThat(versionTwo.capabilities())
                .filteredOn(value -> "REQUIRED".equals(value.responsibilityRequirement()))
                .allMatch(value -> "APP_CONFIG_ADMIN".equals(
                        value.requiredResponsibilityCode()));
        assertThat(versionThree.capabilities())
                .filteredOn(value -> "REQUIRED".equals(value.responsibilityRequirement()))
                .allMatch(value -> "APP_CONFIG_ADMIN".equals(
                        value.requiredResponsibilityCode()));
        assertThat(versionFour.capabilities())
                .filteredOn(value -> "REQUIRED".equals(value.responsibilityRequirement()))
                .allMatch(value -> "APP_CONFIG_ADMIN".equals(
                        value.requiredResponsibilityCode()));
        assertThat(versionFive.capabilities())
                .filteredOn(value -> "REQUIRED".equals(value.responsibilityRequirement()))
                .allMatch(value -> "APP_CONFIG_ADMIN".equals(
                        value.requiredResponsibilityCode()));
    }

    private void assertStrictCapabilitySuperset(
            ProductAuthorizationContractDtos.BundleContract previous,
            ProductAuthorizationContractDtos.BundleContract current) {
        Set<String> priorKeys = previous.capabilities().stream()
                .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .collect(Collectors.toSet());
        Set<String> currentKeys = current.capabilities().stream()
                .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .collect(Collectors.toSet());
        assertThat(currentKeys).containsAll(priorKeys).hasSizeGreaterThan(priorKeys.size());
    }
}
