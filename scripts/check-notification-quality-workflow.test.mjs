import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const workflowPath = new URL(
  "../.github/workflows/notification-backend-postgres.yml",
  import.meta.url,
);
const workflow = readFileSync(workflowPath, "utf8");

test("Notification PostgreSQL CI runs when global Gradle execution changes", () => {
  const requiredPaths = ["gradlew", "gradle/**", "gradle.properties"];
  for (const trigger of ["pull_request", "push"]) {
    const marker = trigger === "pull_request" ? "  pull_request:\n" : "  push:\n";
    const start = workflow.indexOf(marker);
    assert.notEqual(start, -1, `${trigger} trigger must exist`);
    const remaining = workflow.slice(start + marker.length);
    const nextSection = remaining.search(/^ {2}[a-z_]+:\n/mu);
    const triggerSource = nextSection === -1 ? remaining : remaining.slice(0, nextSection);
    for (const requiredPath of requiredPaths) {
      assert.ok(
        triggerSource.includes(`- "${requiredPath}"`),
        `${trigger} ${requiredPath}`,
      );
    }
  }
});

const requiredPostgresClasses = [
  "com.dwp.services.notification.domain.NotificationReasonQueryPostgresIntegrationTest",
  "com.dwp.services.notification.domain.NotificationAttentionGovernanceConcurrencyPostgresIntegrationTest",
  "com.dwp.services.notification.operations.NotificationAttentionAuditPostgresIntegrationTest",
  "com.dwp.services.notification.integration.NotificationStructuredContextMaterializationPostgresIntegrationTest",
  "com.dwp.services.notification.domain.NotificationNoiseQualityHistoryPostgresIntegrationTest",
  "com.dwp.services.notification.integration.ApprovalSlaDeliveryJournalPostgresIntegrationTest",
  "com.dwp.services.notification.integration.ApprovalSlaMaterializationPostgresIntegrationTest",
];

test("Notification PostgreSQL CI provisions every isolated contract database", () => {
  assert.match(workflow, /image: postgres:16\.10-alpine/u);
  for (const database of [
    "dwp_notification_reason_test",
    "dwp_notification_attention_test",
    "dwp_notification_context_test",
    "dwp_notification_sla_test",
  ]) {
    assert.match(workflow, new RegExp(database, "u"), database);
  }
  for (const variable of [
    "DWP_ENVIRONMENT: test",
    "DWP_NOTIFICATION_ATTENTION_TEST_DB_USER",
    "DWP_NOTIFICATION_SLA_TEST_DB_URL",
    "DWP_NOTIFICATION_SLA_TEST_DB_USERNAME",
    "DWP_NOTIFICATION_SLA_TEST_DB_PASSWORD",
  ]) {
    assert.match(workflow, new RegExp(variable, "u"), variable);
  }
});

test("Notification PostgreSQL CI requires all seven real database classes with zero skips", () => {
  assert.match(
    workflow,
    /:dwp-notification-server:cleanTest :dwp-notification-server:test/u,
  );
  assert.match(
    workflow,
    /node --test[\s\S]*scripts\/check-notification-quality-workflow\.test\.mjs/u,
  );
  for (const className of requiredPostgresClasses) {
    assert.match(
      workflow,
      new RegExp(className.replaceAll(".", "\\."), "u"),
      className,
    );
  }
  assert.match(workflow, /tests < 1 or skipped or failures/u);
  assert.match(workflow, /suite\.attrib\.get\('skipped', '0'\)/u);
  assert.match(workflow, /suite\.attrib\.get\('failures', '0'\)/u);
  assert.match(workflow, /suite\.attrib\.get\('errors', '0'\)/u);
  assert.doesNotMatch(workflow, /continue-on-error/u);
});

test("Notification CI keeps the no-automatic-DLT-consumer invariant mandatory", () => {
  assert.match(
    workflow,
    /node --test[\s\S]*scripts\/check-notification-no-dlt-consumer\.test\.mjs/u,
  );
  assert.match(
    workflow,
    /node scripts\/check-notification-no-dlt-consumer\.mjs/u,
  );
  assert.doesNotMatch(workflow, /continue-on-error/u);
});
