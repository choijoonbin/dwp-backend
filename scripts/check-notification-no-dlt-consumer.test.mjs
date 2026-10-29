import assert from "node:assert/strict";
import test from "node:test";

import {
  kafkaListenerAnnotations,
  notificationDltConsumerViolations,
} from "./check-notification-no-dlt-consumer.mjs";

const candidate = (source) => [{ sourcePath: "Fixture.java", source }];

test("extracts compact and multiline Kafka listener annotations without scanning method bodies", () => {
  const source = `
    @KafkaListener(
      topics = "\${dwp.notification.domain-events.topic:dwp.domain-events.v1}",
      groupId = "notification-workers")
    void receive(String payload) { String note = "DLT is operator-only"; }
    @KafkaListener(topics = INPUT_TOPIC, groupId = "other-workers")
    void receiveOther(String payload) {}
  `;

  assert.equal(kafkaListenerAnnotations(source, "Fixture.java").length, 2);
  assert.deepEqual(notificationDltConsumerViolations(candidate(source)), []);
});

test("rejects a DLT default, dead-letter property, or DLT topic constant", () => {
  for (const topics of [
    '"\${events.topic:dwp.domain-events.v1.DLT}"',
    '"\${events.dead-letter-topic}"',
    "DOMAIN_EVENT_DLT_TOPIC",
  ]) {
    const violations = notificationDltConsumerViolations(
      candidate(`@KafkaListener(topics = ${topics}, groupId = "workers")`)
    );
    assert.equal(violations.length, 1, topics);
    assert.match(violations[0], /may not consume a DLT topic/u);
  }
});

test("rejects implicit, pattern, and partition-wide listener bindings", () => {
  for (const annotation of [
    '@KafkaListener(groupId = "workers")',
    '@KafkaListener(topics = "input", topicPattern = ".*", groupId = "workers")',
    '@KafkaListener(topics = "input", topicPartitions = @TopicPartition(topic = "input"))',
  ]) {
    assert.equal(notificationDltConsumerViolations(candidate(annotation)).length, 1, annotation);
  }
});

test("fails closed when a Kafka listener annotation is malformed", () => {
  assert.throws(
    () => kafkaListenerAnnotations('@KafkaListener(topics = "input"', "Fixture.java"),
    /annotation is not closed/u
  );
});
