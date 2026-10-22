package com.dwp.services.people.hris.contracts.hrm012;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Closed in-memory envelope; raw payload is never a persistence or event type. */
public record Hrm012WorkforceInputEnvelopeV1(
        String schemaKey,
        int schemaVersion,
        List<Record> records) {

    private static final Set<String> ENVELOPE_FIELDS =
            Set.of("schemaKey", "schemaVersion", "records");
    private static final Set<String> RECORD_FIELDS =
            Set.of("sourceRecordKey", "sourceVersion", "payload");

    public Hrm012WorkforceInputEnvelopeV1 {
        records = records == null ? List.of() : List.copyOf(records);
        if (!Hrm012WorkforceIngestionContract.canonical(schemaKey, 128)
                || schemaVersion < 1 || records.isEmpty() || records.size() > 10_000) {
            throw Hrm012Problem.invalid("The workforce envelope is invalid.");
        }
    }

    public static Hrm012WorkforceInputEnvelopeV1 parse(
            byte[] bytes, String expectedSchemaKey, int expectedSchemaVersion,
            ObjectMapper sourceMapper) {
        try {
            ObjectMapper mapper = sourceMapper.copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root = mapper.readTree(bytes);
            exactObject(root, ENVELOPE_FIELDS, "envelope");
            String schemaKey = text(root, "schemaKey", 128);
            JsonNode schemaVersionNode = root.get("schemaVersion");
            if (schemaVersionNode == null || !schemaVersionNode.canConvertToInt()
                    || !schemaVersionNode.isIntegralNumber()) {
                throw Hrm012Problem.invalid("Envelope schemaVersion is invalid.");
            }
            int schemaVersion = schemaVersionNode.intValue();
            if (!schemaKey.equals(expectedSchemaKey) || schemaVersion != expectedSchemaVersion) {
                throw Hrm012Problem.invalid("Envelope schema identity does not match the command.");
            }
            JsonNode recordNodes = root.get("records");
            if (recordNodes == null || !recordNodes.isArray()
                    || recordNodes.isEmpty() || recordNodes.size() > 10_000) {
                throw Hrm012Problem.invalid("Envelope records are invalid.");
            }
            List<Record> records = new ArrayList<>(recordNodes.size());
            int sequence = 0;
            for (JsonNode node : recordNodes) {
                sequence++;
                exactObject(node, RECORD_FIELDS, "record");
                String key = text(node, "sourceRecordKey", 256);
                String version = text(node, "sourceVersion", 256);
                JsonNode payload = node.get("payload");
                if (payload == null || !payload.isObject() || payload.isEmpty()) {
                    throw Hrm012Problem.invalid("Record payload is invalid.");
                }
                records.add(new Record(sequence, key, version, payload.deepCopy()));
            }
            return new Hrm012WorkforceInputEnvelopeV1(schemaKey, schemaVersion, records);
        } catch (Hrm012Problem problem) {
            throw problem;
        } catch (Exception exception) {
            throw Hrm012Problem.invalid("The workforce envelope is not closed JSON.");
        }
    }

    private static void exactObject(JsonNode node, Set<String> fields, String label) {
        if (node == null || !node.isObject()) {
            throw Hrm012Problem.invalid("The workforce " + label + " must be an object.");
        }
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) {
            throw Hrm012Problem.invalid("The workforce " + label + " schema is not closed.");
        }
    }

    private static String text(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()
                || !Hrm012WorkforceIngestionContract.canonical(value.textValue(), maximum)) {
            throw Hrm012Problem.invalid("Envelope " + field + " is invalid.");
        }
        return value.textValue();
    }

    public record Record(
            int sequence,
            String sourceRecordKey,
            String sourceVersion,
            JsonNode payload) {
        public Record {
            payload = payload == null ? null : payload.deepCopy();
            if (sequence < 1
                    || !Hrm012WorkforceIngestionContract.canonical(sourceRecordKey, 256)
                    || !Hrm012WorkforceIngestionContract.canonical(sourceVersion, 256)
                    || payload == null || !payload.isObject()) {
                throw Hrm012Problem.invalid("A workforce record is invalid.");
            }
        }

        @Override
        public JsonNode payload() {
            return payload.deepCopy();
        }
    }
}
