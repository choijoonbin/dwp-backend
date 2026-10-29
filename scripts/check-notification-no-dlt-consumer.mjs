import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const LISTENER_MARKER = "@KafkaListener";
const DLT_TOPIC_SIGNAL = /(?:dead[._-]?letter|(?:^|[^a-z0-9])dlt(?:[^a-z0-9]|$))/iu;

export function kafkaListenerAnnotations(source, sourcePath = "<source>") {
  const annotations = [];
  let cursor = 0;
  while ((cursor = source.indexOf(LISTENER_MARKER, cursor)) !== -1) {
    const start = cursor;
    cursor += LISTENER_MARKER.length;
    while (/\s/u.test(source[cursor] ?? "")) cursor += 1;
    if (source[cursor] !== "(") {
      throw new Error(`${sourcePath}: @KafkaListener must include an annotation argument list`);
    }

    let depth = 0;
    let quote = null;
    let escaped = false;
    let closed = false;
    for (; cursor < source.length; cursor += 1) {
      const character = source[cursor];
      if (quote !== null) {
        if (escaped) escaped = false;
        else if (character === "\\") escaped = true;
        else if (character === quote) quote = null;
        continue;
      }
      if (character === '"' || character === "'") {
        quote = character;
        continue;
      }
      if (character === "(") depth += 1;
      if (character !== ")") continue;
      depth -= 1;
      if (depth === 0) {
        cursor += 1;
        annotations.push(source.slice(start, cursor));
        closed = true;
        break;
      }
    }
    if (!closed) throw new Error(`${sourcePath}: @KafkaListener annotation is not closed`);
  }
  return annotations;
}

export function notificationDltConsumerViolations(sources) {
  const violations = [];
  for (const { sourcePath, source } of sources) {
    for (const annotation of kafkaListenerAnnotations(source, sourcePath)) {
      if (!/\btopics\s*=/u.test(annotation)) {
        violations.push(`${sourcePath}: Notification Kafka listeners must bind explicit topics`);
        continue;
      }
      if (/\b(?:topicPattern|topicPartitions)\s*=/u.test(annotation)) {
        violations.push(
          `${sourcePath}: Notification Kafka listeners may not use pattern or partition-wide topic bindings`
        );
        continue;
      }
      if (DLT_TOPIC_SIGNAL.test(annotation)) {
        violations.push(`${sourcePath}: Notification Kafka listeners may not consume a DLT topic`);
      }
    }
  }
  return violations;
}

function javaSources(directory) {
  const entries = readdirSync(directory, { withFileTypes: true });
  return entries.flatMap((entry) => {
    const resolved = path.join(directory, entry.name);
    if (entry.isDirectory()) return javaSources(resolved);
    if (!entry.isFile() || !entry.name.endsWith(".java")) return [];
    return [{ sourcePath: resolved, source: readFileSync(resolved, "utf8") }];
  });
}

function main() {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const sourceRoot = path.join(
    repositoryRoot,
    "dwp-notification-server/src/main/java"
  );
  const sources = javaSources(sourceRoot);
  const listenerCount = sources.reduce(
    (count, candidate) =>
      count + kafkaListenerAnnotations(candidate.source, candidate.sourcePath).length,
    0
  );
  if (listenerCount < 1) {
    throw new Error("Notification DLT guard found no @KafkaListener contracts to inspect");
  }
  const violations = notificationDltConsumerViolations(sources);
  if (violations.length > 0) {
    throw new Error(`Notification DLT consumer guard failed:\n${violations.join("\n")}`);
  }
  process.stdout.write(
    `PASS ${listenerCount} Notification Kafka listeners consume explicit non-DLT topics.\n`
  );
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
