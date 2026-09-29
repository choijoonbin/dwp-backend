#!/usr/bin/env python3
"""Generate the shared, dependency-free HRIS Java contract artifact.

The checked-in JSON schemas and manifest are the only inputs.  The generator
never reads the delivery blueprint, and --check performs no writes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = Path("contracts/hris/canonical/hris-contract-codegen-manifest.v1.json")
JAVA_OUTPUT_ROOT = Path("dwp-platform-contracts/src/main/java")
EXPECTED_MANIFEST_SHA256 = "7b43a95aa815cd622b12b6b2fa19c3695501917bcd88dc08a900ff58f23fd2fa"
SHA256 = re.compile(r"[0-9a-f]{64}")
FQN = re.compile(
    r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)*\.[A-Z][A-Za-z0-9]*"
)
IDENTIFIER = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
SOURCE_KEYS = {
    "sourceId",
    "schemaPath",
    "schemaId",
    "schemaSha256",
    "bindingRegisterProvenance",
    "definitionPrefix",
    "normalizedDigestMode",
    "expectedContractCount",
    "javaPackage",
}
CONTRACT_KEYS = {
    "sourceId",
    "schemaDefinition",
    "javaType",
    "normalizedSchemaSha256",
    "producer",
    "consumers",
}
EXTENSION_BINDING_KEYS = {
    "sourceId",
    "schemaPointer",
    "valueSha256",
    "owner",
    "memberClassifications",
}
EXTENSION_CLASSIFICATIONS = {
    "CODEGEN_TYPE",
    "GENERATED_CONSTRUCTOR",
    "DOMAIN_RUNTIME",
    "SCHEMA_METADATA",
}
LOCAL_EXTENSION_VALUES: dict[tuple[str, str, str], Any] = {
    ("xcon-v1", "#/$defs/ClosedTimeLine/x-unitCurrencyBinding", "MINUTE"): {
        "valueType": "QuantityDecimal", "currency": "FORBIDDEN_NULL"
    },
    ("xcon-v1", "#/$defs/ClosedTimeLine/x-unitCurrencyBinding", "HOUR"): {
        "valueType": "QuantityDecimal", "currency": "FORBIDDEN_NULL"
    },
    ("xcon-v1", "#/$defs/ClosedTimeLine/x-unitCurrencyBinding", "DAY"): {
        "valueType": "QuantityDecimal", "currency": "FORBIDDEN_NULL"
    },
    ("xcon-v1", "#/$defs/ClosedTimeLine/x-unitCurrencyBinding", "COUNT"): {
        "valueType": "QuantityDecimal", "currency": "FORBIDDEN_NULL"
    },
    ("xcon-v1", "#/$defs/ClosedTimeLine/x-unitCurrencyBinding", "AMOUNT"): {
        "valueType": "InputAmount", "currency": "REQUIRED_ISO_4217"
    },
    ("xcon-v1", "#/$defs/XCON_008/x-streamingTransport", "lineCeiling"): 1_000_000,
    ("xcon-v1", "#/$defs/XCON_008/x-streamingTransport", "sequenceValidation"):
        "ARRAY_INDEX_CONTIGUOUS_FROM_0_AND_COUNT_EQUALS_LINE_COUNT",
    ("xcon-v1", "#/$defs/XCON_020/x-invariants", "lineCountEquals"): "lines.length",
    ("xcon-v1", "#/$defs/XCON_020/x-invariants", "lineSequence"):
        "UNIQUE_CONTIGUOUS_ASCENDING_FROM_1",
    ("xcon-v1", "#/$defs/XCON_020/x-invariants", "lineId"):
        "UNIQUE_WITHIN_SNAPSHOT",
    ("xcon-v1", "#/$defs/XCON_020/x-streamingTransport", "lineCeiling"): 100_000,
    ("xcon-v1", "#/$defs/XCON_020/x-streamingTransport", "sequenceValidation"):
        "LINE_SEQUENCE_EQUALS_ARRAY_INDEX_PLUS_1_CONTIGUOUS_NO_GAP_OR_DUPLICATE",
    ("platform-dependency-v1", "#/$defs/PDX_001/x-invariants", "effectiveRange"):
        "HALF_OPEN_NON_OVERLAPPING_FOR_PUBLISHED_BUSINESS_KEY",
    ("platform-dependency-v1", "#/$defs/PDX_001/x-invariants", "publishedApproval"):
        "PUBLISHED_REQUIRES_APPROVAL_RECEIPT_AND_IMMUTABLE_PAYLOAD_DIGEST",
    ("platform-dependency-v1", "#/$defs/PDX_001/x-invariants", "globalScope"):
        "GLOBAL_REQUIRES_NULL_SCOPE_ID_OTHER_SCOPES_REQUIRE_SCOPE_ID",
    ("platform-dependency-v1", "#/$defs/PDX_007/x-invariants", "fourEyes"):
        "APPROVED_BY_ACTOR_MUST_DIFFER_FROM_AUTHORED_BY_ACTOR",
    ("platform-dependency-v1", "#/$defs/PDX_007/x-invariants", "activation"):
        "ACTIVE_REQUIRES_APPROVAL_RECEIPT_AND_DRY_RUN_DIGEST",
    ("platform-dependency-v1", "#/$defs/PDX_007/x-invariants", "effectiveRange"):
        "HALF_OPEN_NON_OVERLAPPING_FOR_CONNECTOR_MAPPING_KEY",
    ("platform-dependency-v1", "#/$defs/PDX_009/x-invariants", "counts"):
        "SOURCE_COUNT_EQUALS_ACCEPTED_PLUS_REJECTED_PLUS_QUARANTINED",
    ("platform-dependency-v1", "#/$defs/PDX_009/x-invariants", "partial"):
        "PARTIAL_REQUIRES_ERROR_REPORT_REFERENCE",
    ("platform-dependency-v1", "#/$defs/PDX_012/x-invariants", "recipients"):
        "RECIPIENTS_MINUS_EXCLUSIONS_MUST_REMAIN_NONEMPTY_AND_BOUNDED",
    ("platform-dependency-v1", "#/$defs/PDX_013/x-invariants", "state"):
        "STATE_CHANNEL_NOTIFICATION_PROVIDER_AND_RETRY_FIELDS_FORM_AN_EXACT_VALID_COMBINATION",
    ("platform-dependency-v1", "#/$defs/PDX_013/x-invariants", "retry"):
        "RETRYABLE_STATE_ALONE_MAY_HAVE_NEXT_ATTEMPT_AND_FAILURE_METADATA",
    ("platform-dependency-v1", "#/$defs/PDX_014/x-invariants", "failure"):
        "DENIED_OR_FAILED_OUTCOME_REQUIRES_ERROR_CODE_AND_EVIDENCE_DIGEST",
}
DECIMAL_DESERIALIZERS = {
    "InputAmount": "InputAmountDeserializer",
    "QuantityDecimal": "QuantityDecimalDeserializer",
    "PostedMoney": "PostedMoneyDeserializer",
}
CANONICAL_SCALAR_SERIALIZERS = {
    "uuid": "UuidSerializer",
    "date": "LocalDateSerializer",
    "date-time": "InstantSerializer",
}
DECIMAL_CODEC_PATH = (
    JAVA_OUTPUT_ROOT
    / "com/dwp/platform/contracts/hris/generated/CanonicalDecimalJson.java"
)
STRICT_JSON_CODEC_PATH = (
    JAVA_OUTPUT_ROOT
    / "com/dwp/platform/contracts/hris/generated/CanonicalHrisJson.java"
)


class GenerationFailure(ValueError):
    """Raised when a code-generation input or output fails closed."""


@dataclass(frozen=True)
class Source:
    source_id: str
    schema_path: Path
    schema_id: str
    definition_prefix: str
    digest_mode: str
    expected_count: int
    java_package: str
    document: dict[str, Any]


@dataclass(frozen=True)
class Contract:
    source: Source
    definition: str
    java_type: str
    normalized_digest: str
    schema: dict[str, Any]

    @property
    def package_name(self) -> str:
        return self.java_type.rsplit(".", 1)[0]

    @property
    def class_name(self) -> str:
        return self.java_type.rsplit(".", 1)[1]


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise GenerationFailure(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_strict_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as exception:
        raise GenerationFailure(f"cannot read {path}: {exception}") from exception


def canonical_digest(value: Any) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def file_digest(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as exception:
        raise GenerationFailure(f"cannot read {path}: {exception}") from exception


def require_object(value: Any, where: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise GenerationFailure(f"{where} must be an object")
    return value


def require_exact_keys(value: dict[str, Any], expected: set[str], where: str) -> None:
    actual = set(value)
    if actual != expected:
        raise GenerationFailure(
            f"{where} keys differ: missing={sorted(expected - actual)}, "
            f"extra={sorted(actual - expected)}"
        )


def safe_repo_path(root: Path, raw: Any, where: str) -> Path:
    if not isinstance(raw, str) or not raw or "\\" in raw:
        raise GenerationFailure(f"{where} must be a non-empty POSIX path")
    relative = PurePosixPath(raw)
    if relative.is_absolute() or ".." in relative.parts:
        raise GenerationFailure(f"{where} must stay within the repository")
    candidate = root.joinpath(*relative.parts)
    try:
        candidate.resolve().relative_to(root.resolve())
    except ValueError as exception:
        raise GenerationFailure(f"{where} escapes the repository") from exception
    if candidate.is_symlink():
        raise GenerationFailure(f"{where} must not be a symbolic link")
    return candidate


def _pointer_token(value: str) -> str:
    return value.replace("~", "~0").replace("/", "~1")


def discover_extensions(document: dict[str, Any]) -> dict[str, Any]:
    extensions: dict[str, Any] = {}

    def visit(value: Any, pointer: str) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                child_pointer = f"{pointer}/{_pointer_token(key)}"
                if key.startswith("x-"):
                    extensions[child_pointer] = child
                visit(child, child_pointer)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                visit(child, f"{pointer}/{index}")

    visit(document, "#")
    return extensions


def validate_extension_bindings(
    raw_bindings: Any, sources_by_id: dict[str, Source]
) -> None:
    if not isinstance(raw_bindings, list) or not raw_bindings:
        raise GenerationFailure("manifest extensionBindings must be a non-empty array")
    discovered = {
        (source_id, pointer): value
        for source_id, source in sources_by_id.items()
        for pointer, value in discover_extensions(source.document).items()
    }
    expected_order = list(discovered)
    actual_order: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()
    locally_enforced: set[tuple[str, str, str]] = set()
    for index, raw_binding in enumerate(raw_bindings):
        where = f"extensionBindings[{index}]"
        binding = require_object(raw_binding, where)
        require_exact_keys(binding, EXTENSION_BINDING_KEYS, where)
        source_id = _non_empty_string(binding["sourceId"], f"{where}.sourceId")
        pointer = _non_empty_string(binding["schemaPointer"], f"{where}.schemaPointer")
        key = (source_id, pointer)
        if key in seen:
            raise GenerationFailure(f"duplicate extension binding {source_id}{pointer}")
        seen.add(key)
        actual_order.append(key)
        if key not in discovered:
            raise GenerationFailure(f"{where} maps an unknown schema extension")
        digest = binding["valueSha256"]
        if not isinstance(digest, str) or SHA256.fullmatch(digest) is None:
            raise GenerationFailure(f"{where}.valueSha256 must be a lowercase SHA-256")
        value = discovered[key]
        if canonical_digest(value) != digest:
            raise GenerationFailure(f"{where} extension value digest drift")
        owner = _non_empty_string(binding["owner"], f"{where}.owner")
        owner_parts = owner.split("|")
        if (
            len(owner_parts) != len(set(owner_parts))
            or any(re.fullmatch(r"[A-Z][A-Z0-9_-]*", part) is None for part in owner_parts)
        ):
            raise GenerationFailure(f"{where}.owner must be an exact unique owner set")
        raw_classifications = require_object(
            binding["memberClassifications"], f"{where}.memberClassifications"
        )
        expected_members = set(value) if isinstance(value, dict) else {"$value"}
        if set(raw_classifications) != expected_members:
            raise GenerationFailure(
                f"{where} extension member closure differs: "
                f"missing={sorted(expected_members - set(raw_classifications))}, "
                f"extra={sorted(set(raw_classifications) - expected_members)}"
            )
        for member, classifications in raw_classifications.items():
            member_where = f"{where}.memberClassifications.{member}"
            if (
                not isinstance(classifications, list)
                or not classifications
                or any(item not in EXTENSION_CLASSIFICATIONS for item in classifications)
                or len(classifications) != len(set(classifications))
            ):
                raise GenerationFailure(f"{member_where} has invalid classifications")
            if pointer.endswith(("/x-invariants", "/x-streamingTransport")) and not set(
                classifications
            ) <= {"GENERATED_CONSTRUCTOR", "DOMAIN_RUNTIME"}:
                raise GenerationFailure(
                    f"{member_where} must be generated locally or owned by DOMAIN_RUNTIME"
                )
            member_key = (source_id, pointer, member)
            member_value = value[member] if isinstance(value, dict) else value
            if "GENERATED_CONSTRUCTOR" in classifications:
                expected_value = LOCAL_EXTENSION_VALUES.get(member_key, object())
                if expected_value != member_value:
                    raise GenerationFailure(
                        f"{member_where} has no exact generated-constructor implementation"
                    )
                locally_enforced.add(member_key)
            elif member_key in LOCAL_EXTENSION_VALUES:
                raise GenerationFailure(
                    f"{member_where} drops its generated-constructor enforcement"
                )

            keyword = pointer.rsplit("/", 1)[1]
            classification_set = set(classifications)
            if keyword == "x-valueType" and classification_set != {"CODEGEN_TYPE"}:
                raise GenerationFailure(f"{member_where} must be CODEGEN_TYPE")
            if keyword == "x-scope" and classification_set != {"SCHEMA_METADATA"}:
                raise GenerationFailure(f"{member_where} must be SCHEMA_METADATA")
            if keyword in {
                "x-sqlType",
                "x-authoritativePointer",
                "x-exponentNotation",
                "x-negativeZero",
                "x-profile",
            } and classification_set != {"DOMAIN_RUNTIME"}:
                raise GenerationFailure(f"{member_where} must be DOMAIN_RUNTIME")
    if seen != set(discovered):
        raise GenerationFailure(
            "schema extension closure differs: "
            f"missing={sorted(set(discovered) - seen)}, extra={sorted(seen - set(discovered))}"
        )
    if actual_order != expected_order:
        raise GenerationFailure("extensionBindings must follow canonical source/pointer order")
    if locally_enforced != set(LOCAL_EXTENSION_VALUES):
        raise GenerationFailure("generated-constructor extension closure is incomplete")


def materialize(value: Any, document: dict[str, Any], seen: tuple[str, ...] = ()) -> Any:
    if isinstance(value, dict) and set(value) == {"$ref"}:
        reference = value["$ref"]
        if not isinstance(reference, str) or not reference.startswith("#/$defs/"):
            raise GenerationFailure(f"external or unsupported schema reference {reference!r}")
        definition = reference.removeprefix("#/$defs/")
        if "/" in definition or not definition:
            raise GenerationFailure(f"unsupported schema reference {reference!r}")
        if definition in seen:
            return {"$recursiveRef": reference}
        definitions = require_object(document.get("$defs"), "$defs")
        if definition not in definitions:
            raise GenerationFailure(f"unknown schema reference {reference!r}")
        return materialize(definitions[definition], document, seen + (definition,))
    if isinstance(value, dict):
        return {
            key: materialize(child, document, seen)
            for key, child in sorted(value.items())
        }
    if isinstance(value, list):
        return [materialize(child, document, seen) for child in value]
    return value


def _non_empty_string(value: Any, where: str) -> str:
    if not isinstance(value, str) or not value:
        raise GenerationFailure(f"{where} must be a non-empty string")
    return value


def load_contracts(root: Path = ROOT) -> tuple[list[Source], list[Contract]]:
    manifest_file = root / MANIFEST_PATH
    manifest = require_object(read_json(manifest_file), "manifest")
    require_exact_keys(
        manifest,
        {
            "$schema",
            "manifestVersion",
            "module",
            "outputRoot",
            "sources",
            "extensionBindings",
            "contracts",
        },
        "manifest",
    )
    if canonical_digest(manifest) != EXPECTED_MANIFEST_SHA256:
        raise GenerationFailure("binding/codegen manifest snapshot digest drift")
    if manifest["$schema"] != "https://json-schema.org/draft/2020-12/schema":
        raise GenerationFailure("manifest must identify JSON Schema draft 2020-12")
    if manifest["manifestVersion"] != 1:
        raise GenerationFailure("unsupported manifestVersion")
    if manifest["module"] != "dwp-platform-contracts":
        raise GenerationFailure("manifest module must be dwp-platform-contracts")
    if manifest["outputRoot"] != JAVA_OUTPUT_ROOT.as_posix():
        raise GenerationFailure("manifest outputRoot must identify the shared contract module")

    raw_sources = manifest["sources"]
    if not isinstance(raw_sources, list) or not raw_sources:
        raise GenerationFailure("manifest sources must be a non-empty array")
    sources: list[Source] = []
    sources_by_id: dict[str, Source] = {}
    for index, raw_source in enumerate(raw_sources):
        where = f"sources[{index}]"
        item = require_object(raw_source, where)
        require_exact_keys(item, SOURCE_KEYS, where)
        source_id = _non_empty_string(item["sourceId"], f"{where}.sourceId")
        if source_id in sources_by_id:
            raise GenerationFailure(f"duplicate sourceId {source_id!r}")
        schema_path = safe_repo_path(root, item["schemaPath"], f"{where}.schemaPath")
        if not schema_path.is_file():
            raise GenerationFailure(f"canonical schema is missing: {item['schemaPath']}")
        expected_digest = item["schemaSha256"]
        if not isinstance(expected_digest, str) or SHA256.fullmatch(expected_digest) is None:
            raise GenerationFailure(f"{where}.schemaSha256 must be a lowercase SHA-256")
        actual_digest = file_digest(schema_path)
        if actual_digest != expected_digest:
            raise GenerationFailure(
                f"canonical schema digest drift for {item['schemaPath']}: "
                f"expected {expected_digest}, got {actual_digest}"
            )
        provenance = require_object(
            item["bindingRegisterProvenance"], f"{where}.bindingRegisterProvenance"
        )
        require_exact_keys(provenance, {"path", "sha256"}, f"{where}.bindingRegisterProvenance")
        _non_empty_string(provenance["path"], f"{where}.bindingRegisterProvenance.path")
        if not isinstance(provenance["sha256"], str) or SHA256.fullmatch(provenance["sha256"]) is None:
            raise GenerationFailure(
                f"{where}.bindingRegisterProvenance.sha256 must be a lowercase SHA-256"
            )
        document = require_object(read_json(schema_path), str(schema_path))
        schema_id = _non_empty_string(item["schemaId"], f"{where}.schemaId")
        if document.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            raise GenerationFailure(f"{where} canonical schema draft drift")
        if document.get("$id") != schema_id:
            raise GenerationFailure(f"{where} canonical schema id drift")
        definitions = require_object(document.get("$defs"), f"{where}.$defs")
        prefix = _non_empty_string(item["definitionPrefix"], f"{where}.definitionPrefix")
        digest_mode = item["normalizedDigestMode"]
        if digest_mode not in {"definition", "materialized"}:
            raise GenerationFailure(f"{where}.normalizedDigestMode is unsupported")
        expected_count = item["expectedContractCount"]
        if type(expected_count) is not int or expected_count < 1:
            raise GenerationFailure(f"{where}.expectedContractCount must be positive")
        package = _non_empty_string(item["javaPackage"], f"{where}.javaPackage")
        if not all(IDENTIFIER.fullmatch(part) for part in package.split(".")):
            raise GenerationFailure(f"{where}.javaPackage is invalid")
        prefixed = {key for key in definitions if key.startswith(prefix)}
        if len(prefixed) != expected_count:
            raise GenerationFailure(
                f"{where} expected {expected_count} {prefix} definitions, found {len(prefixed)}"
            )
        source = Source(
            source_id,
            schema_path,
            schema_id,
            prefix,
            digest_mode,
            expected_count,
            package,
            document,
        )
        sources.append(source)
        sources_by_id[source_id] = source

    validate_extension_bindings(manifest["extensionBindings"], sources_by_id)

    raw_contracts = manifest["contracts"]
    if not isinstance(raw_contracts, list) or not raw_contracts:
        raise GenerationFailure("manifest contracts must be a non-empty array")
    contracts: list[Contract] = []
    seen_definitions: set[tuple[str, str]] = set()
    seen_java_types: set[str] = set()
    for index, raw_contract in enumerate(raw_contracts):
        where = f"contracts[{index}]"
        item = require_object(raw_contract, where)
        require_exact_keys(item, CONTRACT_KEYS, where)
        source_id = _non_empty_string(item["sourceId"], f"{where}.sourceId")
        if source_id not in sources_by_id:
            raise GenerationFailure(f"{where} references unknown sourceId {source_id!r}")
        source = sources_by_id[source_id]
        definition = _non_empty_string(item["schemaDefinition"], f"{where}.schemaDefinition")
        key = (source_id, definition)
        if key in seen_definitions:
            raise GenerationFailure(f"duplicate mapping for {source_id}/{definition}")
        seen_definitions.add(key)
        definitions = source.document["$defs"]
        if definition not in definitions or not definition.startswith(source.definition_prefix):
            raise GenerationFailure(f"{where} references an unknown top-level definition")
        java_type = _non_empty_string(item["javaType"], f"{where}.javaType")
        if FQN.fullmatch(java_type) is None:
            raise GenerationFailure(f"{where}.javaType is not a valid canonical import")
        if java_type.rsplit(".", 1)[0] != source.java_package:
            raise GenerationFailure(f"{where}.javaType must be in {source.java_package}")
        if java_type in seen_java_types:
            raise GenerationFailure(f"duplicate javaType mapping {java_type}")
        seen_java_types.add(java_type)
        normalized_digest = item["normalizedSchemaSha256"]
        if not isinstance(normalized_digest, str) or SHA256.fullmatch(normalized_digest) is None:
            raise GenerationFailure(f"{where}.normalizedSchemaSha256 is invalid")
        schema = require_object(definitions[definition], f"{where} schema")
        normalized = (
            materialize(schema, source.document)
            if source.digest_mode == "materialized"
            else schema
        )
        if canonical_digest(normalized) != normalized_digest:
            raise GenerationFailure(f"{where} normalized schema digest drift")
        if schema.get("type") != "object" or schema.get("additionalProperties") is not False:
            raise GenerationFailure(f"{where} top-level schema must be a closed object")
        properties = require_object(schema.get("properties"), f"{where}.properties")
        required = schema.get("required")
        if (
            not isinstance(required, list)
            or any(not isinstance(name, str) for name in required)
            or len(required) != len(set(required))
            or set(required) != set(properties)
        ):
            raise GenerationFailure(f"{where} required fields must exactly close properties")
        _non_empty_string(item["producer"], f"{where}.producer")
        consumers = item["consumers"]
        if (
            not isinstance(consumers, list)
            or not consumers
            or any(not isinstance(value, str) or not value for value in consumers)
            or len(consumers) != len(set(consumers))
        ):
            raise GenerationFailure(f"{where}.consumers must be a unique non-empty string array")
        contracts.append(Contract(source, definition, java_type, normalized_digest, schema))

    expected_total = sum(source.expected_count for source in sources)
    if len(contracts) != expected_total:
        raise GenerationFailure(
            f"manifest must map exactly {expected_total} contracts, found {len(contracts)}"
        )
    for source in sources:
        expected = {
            name
            for name in source.document["$defs"]
            if name.startswith(source.definition_prefix)
        }
        actual = {
            contract.definition for contract in contracts if contract.source == source
        }
        if actual != expected:
            raise GenerationFailure(
                f"{source.source_id} mapping closure differs: "
                f"missing={sorted(expected - actual)}, extra={sorted(actual - expected)}"
            )
    return sources, sorted(contracts, key=lambda contract: contract.java_type)


def dereference(schema: dict[str, Any], source: Source) -> tuple[dict[str, Any], str | None]:
    if set(schema) != {"$ref"}:
        return schema, None
    reference = schema["$ref"]
    if not isinstance(reference, str) or not reference.startswith("#/$defs/"):
        raise GenerationFailure(f"unsupported schema reference {reference!r}")
    name = reference.removeprefix("#/$defs/")
    if "/" in name or name not in source.document["$defs"]:
        raise GenerationFailure(f"unknown schema reference {reference!r}")
    return require_object(source.document["$defs"][name], reference), name


def accepted_types(schema: dict[str, Any]) -> tuple[str, ...]:
    value = schema.get("type")
    if isinstance(value, str):
        return (value,)
    if isinstance(value, list) and value and all(isinstance(item, str) for item in value):
        if len(value) != len(set(value)):
            raise GenerationFailure("schema type array contains duplicates")
        return tuple(value)
    raise GenerationFailure(f"schema has unsupported type {value!r}")


def schema_nullable(schema: dict[str, Any], source: Source) -> bool:
    resolved, _ = dereference(schema, source)
    return "null" in accepted_types(resolved)


def decimal_value_type(schema: dict[str, Any], source: Source) -> str | None:
    resolved, _ = dereference(schema, source)
    value_type = resolved.get("x-valueType")
    if value_type is None:
        return None
    if accepted_types(resolved) not in {("string",), ("string", "null")}:
        raise GenerationFailure("canonical decimal value type must be a string schema")
    if value_type not in DECIMAL_DESERIALIZERS:
        raise GenerationFailure(f"unsupported canonical decimal value type {value_type!r}")
    if not isinstance(resolved.get("pattern"), str):
        raise GenerationFailure(f"{value_type} must define a canonical string pattern")
    return value_type


def boxed(java_type: str) -> str:
    return {"int": "Integer", "long": "Long", "boolean": "Boolean"}.get(
        java_type, java_type
    )


def java_type(schema: dict[str, Any], source: Source, required: bool = True) -> str:
    resolved, reference_name = dereference(schema, source)
    types = tuple(value for value in accepted_types(resolved) if value != "null")
    if len(types) != 1:
        raise GenerationFailure(f"schema has unsupported union type {types!r}")
    schema_type = types[0]
    nullable = not required or "null" in accepted_types(resolved)
    if schema_type == "string":
        decimal_type = decimal_value_type(schema, source)
        if decimal_type is not None:
            result = "BigDecimal"
        elif resolved.get("format") == "uuid":
            result = "UUID"
        elif resolved.get("format") == "date":
            result = "LocalDate"
        elif resolved.get("format") == "date-time":
            result = "Instant"
        else:
            result = "String"
    elif schema_type == "integer":
        minimum = resolved.get("minimum", -(2**63))
        maximum = resolved.get("maximum", 2**63 - 1)
        result = "int" if -(2**31) <= minimum and maximum <= 2**31 - 1 else "long"
    elif schema_type == "number":
        result = "BigDecimal"
    elif schema_type == "boolean":
        result = "boolean"
    elif schema_type == "array":
        items = require_object(resolved.get("items"), "array items")
        result = f"List<{boxed(java_type(items, source))}>"
    elif schema_type == "object":
        if reference_name is not None:
            result = reference_name
        elif isinstance(resolved.get("additionalProperties"), dict):
            value_type = boxed(java_type(resolved["additionalProperties"], source))
            result = f"Map<String, {value_type}>"
        else:
            raise GenerationFailure("inline closed objects must be named in $defs")
    else:
        raise GenerationFailure(f"unsupported JSON Schema type {schema_type!r}")
    return boxed(result) if nullable else result


def java_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=True)


def validation_lines(
    name: str,
    raw_schema: dict[str, Any],
    source: Source,
    required: bool,
    indent: str,
) -> list[str]:
    schema, _ = dereference(raw_schema, source)
    types = accepted_types(schema)
    concrete = tuple(value for value in types if value != "null")
    if len(concrete) != 1:
        raise GenerationFailure(f"unsupported validation union for {name}")
    schema_type = concrete[0]
    nullable = not required or "null" in types
    field_type = java_type(raw_schema, source, required)
    lines: list[str] = []
    guard_indent = indent
    constrained = (
        (schema_type == "string" and any(
            key in schema for key in ("minLength", "maxLength", "pattern", "enum", "const")
        ))
        or (schema_type in {"integer", "number"} and any(
            key in schema for key in ("minimum", "maximum")
        ))
        or schema_type == "array"
        or (schema_type == "object" and field_type.startswith("Map<"))
    )
    if nullable and not constrained:
        return lines
    if schema_type in {"string", "array", "object", "number"}:
        if not nullable:
            lines.append(f'{indent}Objects.requireNonNull({name}, "{name} must not be null");')
        else:
            lines.append(f"{indent}if ({name} != null) {{")
            guard_indent += "    "
    elif nullable:
        lines.append(f"{indent}if ({name} != null) {{")
        guard_indent += "    "

    if schema_type == "string" and field_type.endswith("String"):
        if "minLength" in schema:
            lines.append(
                f"{guard_indent}if ({name}.length() < {schema['minLength']}) {{"
            )
            lines.append(
                f'{guard_indent}    throw new IllegalArgumentException("{name} is shorter than {schema["minLength"]}");'
            )
            lines.append(f"{guard_indent}}}")
        if "maxLength" in schema:
            lines.append(
                f"{guard_indent}if ({name}.length() > {schema['maxLength']}) {{"
            )
            lines.append(
                f'{guard_indent}    throw new IllegalArgumentException("{name} is longer than {schema["maxLength"]}");'
            )
            lines.append(f"{guard_indent}}}")
        if "pattern" in schema:
            lines.append(
                f"{guard_indent}if (!Pattern.matches({java_string(schema['pattern'])}, {name})) {{"
            )
            lines.append(
                f'{guard_indent}    throw new IllegalArgumentException("{name} does not match its canonical format");'
            )
            lines.append(f"{guard_indent}}}")
        if "enum" in schema:
            values = ", ".join(java_string(value) for value in schema["enum"])
            lines.append(f"{guard_indent}if (!Set.of({values}).contains({name})) {{")
            lines.append(
                f'{guard_indent}    throw new IllegalArgumentException("{name} is not a canonical value");'
            )
            lines.append(f"{guard_indent}}}")
        if "const" in schema:
            value = java_string(schema["const"])
            lines.append(f"{guard_indent}if (!{value}.equals({name})) {{")
            lines.append(
                f'{guard_indent}    throw new IllegalArgumentException("{name} must equal " + {value});'
            )
            lines.append(f"{guard_indent}}}")
    elif schema_type == "string" and field_type.endswith("BigDecimal"):
        lines.append(
            f"{guard_indent}{name} = CanonicalDecimalJson.normalize({name});"
        )
        if "pattern" in schema:
            lines.append(
                f"{guard_indent}if (!Pattern.matches({java_string(schema['pattern'])}, {name}.toPlainString())) {{"
            )
            lines.append(
                f'{guard_indent}    throw new IllegalArgumentException("{name} does not match its canonical decimal format");'
            )
            lines.append(f"{guard_indent}}}")
    elif schema_type in {"integer", "number"}:
        if schema_type == "number":
            if "minimum" in schema:
                value = java_string(str(schema["minimum"]))
                lines.append(
                    f"{guard_indent}if ({name}.compareTo(new BigDecimal({value})) < 0) {{"
                )
                lines.append(
                    f'{guard_indent}    throw new IllegalArgumentException("{name} is below its canonical minimum");'
                )
                lines.append(f"{guard_indent}}}")
            if "maximum" in schema:
                value = java_string(str(schema["maximum"]))
                lines.append(
                    f"{guard_indent}if ({name}.compareTo(new BigDecimal({value})) > 0) {{"
                )
                lines.append(
                    f'{guard_indent}    throw new IllegalArgumentException("{name} exceeds its canonical maximum");'
                )
                lines.append(f"{guard_indent}}}")
        else:
            if "minimum" in schema:
                lines.append(f"{guard_indent}if ({name} < {schema['minimum']}L) {{")
                lines.append(
                    f'{guard_indent}    throw new IllegalArgumentException("{name} is below its canonical minimum");'
                )
                lines.append(f"{guard_indent}}}")
            if "maximum" in schema:
                lines.append(f"{guard_indent}if ({name} > {schema['maximum']}L) {{")
                lines.append(
                    f'{guard_indent}    throw new IllegalArgumentException("{name} exceeds its canonical maximum");'
                )
                lines.append(f"{guard_indent}}}")
    elif schema_type == "array":
        lines.append(f"{guard_indent}{name} = List.copyOf({name});")
        if "minItems" in schema:
            lines.append(f"{guard_indent}if ({name}.size() < {schema['minItems']}) {{")
            lines.append(
                f'{guard_indent}    throw new IllegalArgumentException("{name} has too few items");'
            )
            lines.append(f"{guard_indent}}}")
        if "maxItems" in schema:
            lines.append(f"{guard_indent}if ({name}.size() > {schema['maxItems']}) {{")
            lines.append(
                f'{guard_indent}    throw new IllegalArgumentException("{name} has too many items");'
            )
            lines.append(f"{guard_indent}}}")
        if schema.get("uniqueItems") is True:
            lines.append(f"{guard_indent}if (new HashSet<>({name}).size() != {name}.size()) {{")
            lines.append(
                f'{guard_indent}    throw new IllegalArgumentException("{name} must contain unique items");'
            )
            lines.append(f"{guard_indent}}}")
        item_schema = require_object(schema.get("items"), f"{name}.items")
        item_name = f"{name}Item"
        item_type = boxed(java_type(item_schema, source))
        lines.append(f"{guard_indent}for ({item_type} {item_name} : {name}) {{")
        lines.extend(
            validation_lines(
                item_name,
                item_schema,
                source,
                True,
                guard_indent + "    ",
            )
        )
        lines.append(f"{guard_indent}}}")
    elif schema_type == "object" and field_type.startswith("Map<"):
        lines.append(f"{guard_indent}{name} = Map.copyOf({name});")

    if nullable:
        lines.append(f"{indent}}}")
    return lines


def conditional_specs(
    schema: dict[str, Any], source: Source, where: str
) -> list[tuple[str, str, dict[str, Any], dict[str, Any]]]:
    """Validate and return the supported object if/then/else schema shape.

    Canonical conditionals are deliberately narrow: each allOf member selects
    one required string property by const and applies closed property constraints
    in both branches. Any other composition shape is rejected instead of being
    silently omitted from the Java boundary.
    """
    for unsupported in ("anyOf", "oneOf", "not", "dependentSchemas"):
        if unsupported in schema:
            raise GenerationFailure(f"{where} uses unsupported keyword {unsupported}")
    if any(keyword in schema for keyword in ("if", "then", "else")):
        raise GenerationFailure(f"{where} conditionals must be members of allOf")
    raw_all_of = schema.get("allOf", [])
    if not isinstance(raw_all_of, list):
        raise GenerationFailure(f"{where}.allOf must be an array")
    base_properties = require_object(schema.get("properties"), f"{where}.properties")
    specs: list[tuple[str, str, dict[str, Any], dict[str, Any]]] = []
    for index, raw_clause in enumerate(raw_all_of):
        clause_where = f"{where}.allOf[{index}]"
        clause = require_object(raw_clause, clause_where)
        require_exact_keys(clause, {"if", "then", "else"}, clause_where)
        predicate = require_object(clause["if"], f"{clause_where}.if")
        require_exact_keys(predicate, {"properties", "required"}, f"{clause_where}.if")
        predicate_properties = require_object(
            predicate["properties"], f"{clause_where}.if.properties"
        )
        predicate_required = predicate["required"]
        if (
            not isinstance(predicate_required, list)
            or len(predicate_required) != 1
            or set(predicate_properties) != set(predicate_required)
        ):
            raise GenerationFailure(
                f"{clause_where}.if must select exactly one required property"
            )
        control = predicate_required[0]
        if control not in base_properties:
            raise GenerationFailure(f"{clause_where}.if selects unknown property {control}")
        selector = require_object(
            predicate_properties[control], f"{clause_where}.if.properties.{control}"
        )
        require_exact_keys(selector, {"const"}, f"{clause_where}.if.properties.{control}")
        const_value = selector["const"]
        if not isinstance(const_value, str):
            raise GenerationFailure(f"{clause_where} supports string const selectors only")
        control_schema, _ = dereference(
            require_object(base_properties[control], control), source
        )
        if "enum" in control_schema and const_value not in control_schema["enum"]:
            raise GenerationFailure(f"{clause_where} const is outside the base enum")

        branches: list[dict[str, Any]] = []
        for branch_name in ("then", "else"):
            branch_where = f"{clause_where}.{branch_name}"
            branch = require_object(clause[branch_name], branch_where)
            require_exact_keys(branch, {"properties", "required"}, branch_where)
            branch_properties = require_object(
                branch["properties"], f"{branch_where}.properties"
            )
            branch_required = branch["required"]
            if (
                not isinstance(branch_required, list)
                or any(not isinstance(name, str) for name in branch_required)
                or len(branch_required) != len(set(branch_required))
                or set(branch_required) != set(branch_properties)
            ):
                raise GenerationFailure(
                    f"{branch_where} required fields must exactly close properties"
                )
            for property_name, branch_property in branch_properties.items():
                if property_name not in base_properties:
                    raise GenerationFailure(
                        f"{branch_where} constrains unknown property {property_name}"
                    )
                resolved, _ = dereference(
                    require_object(branch_property, f"{branch_where}.{property_name}"),
                    source,
                )
                types = accepted_types(resolved)
                if types == ("null",):
                    if not schema_nullable(
                        require_object(base_properties[property_name], property_name), source
                    ):
                        raise GenerationFailure(
                            f"{branch_where}.{property_name} requires null but base is non-null"
                        )
                    continue
                base_type = boxed(java_type(
                    require_object(base_properties[property_name], property_name), source
                ))
                branch_type = boxed(java_type(
                    require_object(branch_property, property_name), source
                ))
                if base_type != branch_type:
                    raise GenerationFailure(
                        f"{branch_where}.{property_name} changes Java type "
                        f"from {base_type} to {branch_type}"
                    )
            branches.append(branch)
        specs.append((control, const_value, branches[0], branches[1]))
    return specs


def conditional_validation_lines(
    schema: dict[str, Any], source: Source, where: str, indent: str
) -> list[str]:
    lines: list[str] = []
    for control, const_value, then_branch, else_branch in conditional_specs(
        schema, source, where
    ):
        lines.append(f"{indent}if ({java_string(const_value)}.equals({control})) {{")
        for name, raw_child in then_branch["properties"].items():
            child = require_object(raw_child, f"{where}.then.{name}")
            resolved, _ = dereference(child, source)
            if accepted_types(resolved) == ("null",):
                lines.append(f"{indent}    if ({name} != null) {{")
                lines.append(
                    f'{indent}        throw new IllegalArgumentException("{name} must be null when {control} is {const_value}");'
                )
                lines.append(f"{indent}    }}")
            else:
                lines.extend(validation_lines(name, child, source, True, indent + "    "))
        lines.append(f"{indent}}} else {{")
        for name, raw_child in else_branch["properties"].items():
            child = require_object(raw_child, f"{where}.else.{name}")
            resolved, _ = dereference(child, source)
            if accepted_types(resolved) == ("null",):
                lines.append(f"{indent}    if ({name} != null) {{")
                lines.append(
                    f'{indent}        throw new IllegalArgumentException("{name} must be null unless {control} is {const_value}");'
                )
                lines.append(f"{indent}    }}")
            else:
                lines.extend(validation_lines(name, child, source, True, indent + "    "))
        lines.append(f"{indent}}}")
    return lines


def extension_validation_lines(
    schema: dict[str, Any], source: Source, where: str, indent: str
) -> list[str]:
    """Emit validation for schema extensions classified GENERATED_CONSTRUCTOR."""
    unit_binding = schema.get("x-unitCurrencyBinding")
    if unit_binding is None:
        return []
    expected_binding = {
        unit: LOCAL_EXTENSION_VALUES[(source.source_id, f"#/$defs/{where}/x-unitCurrencyBinding", unit)]
        for unit in ("MINUTE", "HOUR", "DAY", "COUNT", "AMOUNT")
        if (source.source_id, f"#/$defs/{where}/x-unitCurrencyBinding", unit)
        in LOCAL_EXTENSION_VALUES
    }
    if unit_binding != expected_binding or set(expected_binding) != {
        "MINUTE", "HOUR", "DAY", "COUNT", "AMOUNT"
    }:
        raise GenerationFailure(
            f"{where}.x-unitCurrencyBinding has no exact generated validator"
        )
    return [
        f'{indent}if ("AMOUNT".equals(unit)) {{',
        f"{indent}    try {{",
        f"{indent}        Currency.getInstance(currency);",
        f"{indent}    }} catch (IllegalArgumentException exception) {{",
        f'{indent}        throw new IllegalArgumentException("currency must be a recognized ISO 4217 code", exception);',
        f"{indent}    }}",
        f"{indent}}}",
    ]


def local_invariant_validation_lines(
    source_id: str, schema_key: str, indent: str
) -> list[str]:
    key = (source_id, schema_key)
    if key == ("xcon-v1", "XCON_008"):
        return [
            f"{indent}if (lineCount != lines.size()) {{",
            f'{indent}    throw new IllegalArgumentException("lineCount must equal lines.size");',
            f"{indent}}}",
        ]
    if key == ("xcon-v1", "XCON_020"):
        return [
            f"{indent}if (lineCount != lines.size()) {{",
            f'{indent}    throw new IllegalArgumentException("lineCount must equal lines.size");',
            f"{indent}}}",
            f"{indent}for (int index = 0; index < lines.size(); index++) {{",
            f"{indent}    if (lines.get(index).lineSequence() != index + 1) {{",
            f'{indent}        throw new IllegalArgumentException("lineSequence must be contiguous from 1 in list order");',
            f"{indent}    }}",
            f"{indent}}}",
            f"{indent}if (lines.stream().map(ApprovedCompensationPlanLine::lineId).distinct().count() != lines.size()) {{",
            f'{indent}    throw new IllegalArgumentException("lineId must be unique within the snapshot");',
            f"{indent}}}",
        ]
    if key == ("platform-dependency-v1", "PDX_001"):
        return [
            f"{indent}if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {{",
            f'{indent}    throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");',
            f"{indent}}}",
            f"{indent}if (\"GLOBAL\".equals(scopeType) && scopeId != null) {{",
            f'{indent}    throw new IllegalArgumentException("scopeId must be null for GLOBAL scope");',
            f"{indent}}}",
            f"{indent}if (!\"GLOBAL\".equals(scopeType) && scopeId == null) {{",
            f'{indent}    throw new IllegalArgumentException("scopeId is required for non-GLOBAL scope");',
            f"{indent}}}",
            f"{indent}if (\"PUBLISHED\".equals(state) && approvalReceiptId == null) {{",
            f'{indent}    throw new IllegalArgumentException("approvalReceiptId is required for PUBLISHED state");',
            f"{indent}}}",
        ]
    if key == ("platform-dependency-v1", "PDX_007"):
        return [
            f"{indent}if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {{",
            f'{indent}    throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");',
            f"{indent}}}",
            f"{indent}if (approvedByActorRef != null && approvedByActorRef.equals(authoredByActorRef)) {{",
            f'{indent}    throw new IllegalArgumentException("approvedByActorRef must differ from authoredByActorRef");',
            f"{indent}}}",
            f"{indent}if (\"ACTIVE\".equals(state) && approvedByActorRef == null) {{",
            f'{indent}    throw new IllegalArgumentException("approvedByActorRef is required for ACTIVE state");',
            f"{indent}}}",
            f"{indent}if (\"ACTIVE\".equals(state) && approvalReceiptId == null) {{",
            f'{indent}    throw new IllegalArgumentException("approvalReceiptId is required for ACTIVE state");',
            f"{indent}}}",
            f"{indent}if (\"ACTIVE\".equals(state) && dryRunDigest == null) {{",
            f'{indent}    throw new IllegalArgumentException("dryRunDigest is required for ACTIVE state");',
            f"{indent}}}",
        ]
    if key == ("platform-dependency-v1", "PDX_009"):
        return [
            f"{indent}if ((long) sourceCount != (long) acceptedCount + rejectedCount + quarantinedCount) {{",
            f'{indent}    throw new IllegalArgumentException("sourceCount must equal acceptedCount + rejectedCount + quarantinedCount");',
            f"{indent}}}",
            f"{indent}if (\"PARTIAL\".equals(state) && errorReportRef == null) {{",
            f'{indent}    throw new IllegalArgumentException("errorReportRef is required for PARTIAL state");',
            f"{indent}}}",
        ]
    if key == ("platform-dependency-v1", "PDX_012"):
        return [
            f"{indent}if (recipientUserIds.stream().allMatch(excludedUserIds::contains)) {{",
            f'{indent}    throw new IllegalArgumentException("recipientUserIds minus excludedUserIds must remain non-empty");',
            f"{indent}}}",
        ]
    if key == ("platform-dependency-v1", "PDX_013"):
        return [
            f'{indent}boolean failedState = "FAILED".equals(state) || "DEAD_LETTERED".equals(state);',
            f"{indent}if (failedState && errorCode == null) {{",
            f'{indent}    throw new IllegalArgumentException("errorCode is required for FAILED or DEAD_LETTERED state");',
            f"{indent}}}",
            f"{indent}if (!failedState && errorCode != null) {{",
            f'{indent}    throw new IllegalArgumentException("errorCode is forbidden outside FAILED or DEAD_LETTERED state");',
            f"{indent}}}",
            f'{indent}if (retryable && !"FAILED".equals(state)) {{',
            f'{indent}    throw new IllegalArgumentException("only FAILED state may be retryable");',
            f"{indent}}}",
            f"{indent}if (retryable != (nextAttemptAt != null)) {{",
            f'{indent}    throw new IllegalArgumentException("nextAttemptAt must be present exactly when retryable");',
            f"{indent}}}",
            f'{indent}boolean channelNotSelected = "NONE".equals(channel);',
            f'{indent}boolean preMaterialization = Set.of("RECEIVED", "ADMITTED", "SUPPRESSED").contains(state);',
            f"{indent}if (preMaterialization && (!channelNotSelected || notificationId != null",
            f"{indent}        || materializedDigest != null || providerReceiptDigest != null",
            f"{indent}        || attemptNumber != 0 || errorCode != null || retryable || nextAttemptAt != null)) {{",
            f'{indent}    throw new IllegalArgumentException("pre-materialization state has non-canonical delivery fields");',
            f"{indent}}}",
            f'{indent}boolean postMaterialization = Set.of("MATERIALIZED", "QUEUED", "DELIVERED",',
            f'{indent}        "FAILED", "DEAD_LETTERED", "CANCELED").contains(state);',
            f"{indent}if (postMaterialization && (channelNotSelected",
            f"{indent}        || notificationId == null || materializedDigest == null)) {{",
            f'{indent}    throw new IllegalArgumentException("post-materialization state requires channel, notificationId, and materializedDigest");',
            f"{indent}}}",
            f'{indent}boolean notAttempted = "MATERIALIZED".equals(state) || "QUEUED".equals(state);',
            f"{indent}if (notAttempted && (attemptNumber != 0 || providerReceiptDigest != null)) {{",
            f'{indent}    throw new IllegalArgumentException("unattempted delivery state requires attemptNumber zero and no provider receipt");',
            f"{indent}}}",
            f'{indent}boolean inApp = "IN_APP".equals(channel);',
            f"{indent}if (inApp && providerReceiptDigest != null) {{",
            f'{indent}    throw new IllegalArgumentException("IN_APP channel forbids providerReceiptDigest");',
            f"{indent}}}",
            f'{indent}if ("DELIVERED".equals(state) && (inApp != (attemptNumber == 0)',
            f"{indent}        || (!inApp && providerReceiptDigest == null))) {{",
            f'{indent}    throw new IllegalArgumentException("DELIVERED attempt and provider receipt fields do not match channel");',
            f"{indent}}}",
            f"{indent}if (failedState && attemptNumber < 1) {{",
            f'{indent}    throw new IllegalArgumentException("FAILED or DEAD_LETTERED state requires an attempted delivery");',
            f"{indent}}}",
            f'{indent}if ("CANCELED".equals(state) && providerReceiptDigest != null) {{',
            f'{indent}    throw new IllegalArgumentException("CANCELED state forbids providerReceiptDigest");',
            f"{indent}}}",
        ]
    if key == ("platform-dependency-v1", "PDX_014"):
        return [
            f'{indent}boolean unsuccessful = "DENIED".equals(outcome) || "FAILED".equals(outcome);',
            f"{indent}if (unsuccessful && errorCode == null) {{",
            f'{indent}    throw new IllegalArgumentException("errorCode is required for DENIED or FAILED outcome");',
            f"{indent}}}",
            f"{indent}if (!unsuccessful && errorCode != null) {{",
            f'{indent}    throw new IllegalArgumentException("errorCode is forbidden for SUCCESS outcome");',
            f"{indent}}}",
        ]
    return []


def referenced_object_definitions(schema: dict[str, Any], source: Source) -> list[str]:
    found: set[str] = set()

    def visit(value: Any, active: tuple[str, ...]) -> None:
        if isinstance(value, dict):
            if set(value) == {"$ref"}:
                reference = value["$ref"]
                if not isinstance(reference, str) or not reference.startswith("#/$defs/"):
                    raise GenerationFailure(f"unsupported schema reference {reference!r}")
                name = reference.removeprefix("#/$defs/")
                definitions = source.document["$defs"]
                if name not in definitions:
                    raise GenerationFailure(f"unknown schema reference {reference!r}")
                if name in active:
                    raise GenerationFailure(f"recursive Java contract type is unsupported: {name}")
                target = require_object(definitions[name], reference)
                if target.get("type") == "object":
                    found.add(name)
                visit(target, active + (name,))
            else:
                for child in value.values():
                    visit(child, active)
        elif isinstance(value, list):
            for child in value:
                visit(child, active)

    visit(schema, ())
    return sorted(found)


def collect_imports(contract: Contract, object_names: list[str]) -> list[str]:
    schemas = [contract.schema] + [contract.source.document["$defs"][name] for name in object_names]
    rendered_types: set[str] = set()
    needs_pattern = False
    needs_set = False
    needs_hash_set = False
    needs_currency = False
    needs_decimal_codec = False
    needs_canonical_scalar_serializer = False
    needs_array = False
    for schema in schemas:
        conditional_specs(schema, contract.source, "generated record")
        required = set(schema.get("required", []))
        for name, child in require_object(schema.get("properties"), "properties").items():
            child_schema = require_object(child, f"property {name}")
            rendered_types.add(java_type(child_schema, contract.source, name in required))
            resolved, _ = dereference(child_schema, contract.source)
            needs_canonical_scalar_serializer = (
                needs_canonical_scalar_serializer
                or resolved.get("format") in CANONICAL_SCALAR_SERIALIZERS
            )
            if "array" in accepted_types(resolved):
                item_schema = require_object(resolved.get("items"), f"{name}.items")
                item_resolved, _ = dereference(item_schema, contract.source)
                needs_canonical_scalar_serializer = (
                    needs_canonical_scalar_serializer
                    or item_resolved.get("format") in CANONICAL_SCALAR_SERIALIZERS
                )
            needs_decimal_codec = (
                needs_decimal_codec
                or decimal_value_type(child_schema, contract.source) is not None
            )
            if "pattern" in resolved:
                needs_pattern = True
            if "enum" in resolved:
                needs_set = True
            if resolved.get("uniqueItems") is True:
                needs_hash_set = True
            if "array" in accepted_types(resolved):
                needs_array = True
        materialized = materialize(schema, contract.source.document)
        encoded = json.dumps(materialized, sort_keys=True)
        needs_pattern = needs_pattern or '"pattern"' in encoded
        needs_set = needs_set or '"enum"' in encoded
        needs_hash_set = needs_hash_set or '"uniqueItems": true' in encoded
        needs_currency = needs_currency or "x-unitCurrencyBinding" in schema
    imports = {
        "com.dwp.platform.contracts.hris.generated.CanonicalHrisJson",
        "com.fasterxml.jackson.core.JsonParser",
        "com.fasterxml.jackson.core.JsonToken",
        "com.fasterxml.jackson.databind.DeserializationContext",
        "com.fasterxml.jackson.databind.JsonDeserializer",
        "com.fasterxml.jackson.annotation.JsonInclude",
        "com.fasterxml.jackson.databind.annotation.JsonDeserialize",
        "java.io.IOException",
        "java.util.Objects",
    }
    joined = " ".join(rendered_types)
    for simple, imported in {
        "BigDecimal": "java.math.BigDecimal",
        "Instant": "java.time.Instant",
        "LocalDate": "java.time.LocalDate",
        "UUID": "java.util.UUID",
        "List<": "java.util.List",
        "Map<": "java.util.Map",
    }.items():
        if simple in joined:
            imports.add(imported)
    if needs_pattern:
        imports.add("java.util.regex.Pattern")
    if needs_set:
        imports.add("java.util.Set")
    if needs_hash_set:
        imports.add("java.util.HashSet")
    if needs_array:
        imports.add("java.util.ArrayList")
    if needs_currency:
        imports.add("java.util.Currency")
    if needs_decimal_codec:
        imports.add("com.fasterxml.jackson.databind.annotation.JsonDeserialize")
        imports.add("com.fasterxml.jackson.databind.annotation.JsonSerialize")
        imports.add(
            "com.dwp.platform.contracts.hris.generated.CanonicalDecimalJson"
        )
    if needs_canonical_scalar_serializer:
        imports.add("com.fasterxml.jackson.databind.annotation.JsonSerialize")
    return sorted(imports)


def java_default(java_name: str) -> str:
    if java_name == "int":
        return "0"
    if java_name == "long":
        return "0L"
    if java_name == "boolean":
        return "false"
    return "null"


def strict_read_expression(
    name: str,
    raw_schema: dict[str, Any],
    source: Source,
    required: bool,
) -> str:
    schema, reference_name = dereference(raw_schema, source)
    types = accepted_types(schema)
    concrete = tuple(value for value in types if value != "null")
    if len(concrete) != 1:
        raise GenerationFailure(f"unsupported strict wire union for {name}")
    schema_type = concrete[0]
    nullable = not required or "null" in types
    nullable_literal = str(nullable).lower()
    if schema_type == "string":
        value_type = decimal_value_type(raw_schema, source)
        if value_type is not None:
            if nullable:
                raise GenerationFailure("nullable canonical decimals are unsupported")
            deserializer = DECIMAL_DESERIALIZERS[value_type]
            return (
                f"new CanonicalDecimalJson.{deserializer}()"
                ".deserialize(parser, context)"
            )
        format_name = schema.get("format")
        if format_name == "uuid":
            return (
                f'CanonicalHrisJson.readUuid(parser, context, "{name}", '
                f"{nullable_literal})"
            )
        if format_name == "date":
            return (
                f'CanonicalHrisJson.readDate(parser, context, "{name}", '
                f"{nullable_literal})"
            )
        if format_name == "date-time":
            return (
                f'CanonicalHrisJson.readInstant(parser, context, "{name}", '
                f"{nullable_literal})"
            )
        return (
            f'CanonicalHrisJson.readString(parser, context, "{name}", '
            f"{nullable_literal})"
        )
    if schema_type == "integer":
        method = "readInt" if java_type(raw_schema, source, required) == "int" else "readLong"
        return (
            f'CanonicalHrisJson.{method}(parser, context, "{name}", '
            f"{nullable_literal})"
        )
    if schema_type == "number":
        return (
            f'CanonicalHrisJson.readNumber(parser, context, "{name}", '
            f"{nullable_literal})"
        )
    if schema_type == "boolean":
        return (
            f'CanonicalHrisJson.readBoolean(parser, context, "{name}", '
            f"{nullable_literal})"
        )
    if schema_type == "object" and reference_name is not None:
        return (
            f'CanonicalHrisJson.readObject(parser, context, "{name}", '
            f"{reference_name}.class, {nullable_literal})"
        )
    if schema_type == "object" and isinstance(schema.get("additionalProperties"), dict):
        raise GenerationFailure("strict wire map fields are not implemented")
    if schema_type == "array":
        raise GenerationFailure("strict array fields require generated loop handling")
    raise GenerationFailure(f"unsupported strict wire type {schema_type!r} for {name}")


def strict_assignment_lines(
    name: str,
    raw_schema: dict[str, Any],
    source: Source,
    required: bool,
    target_class: str,
    indent: str,
) -> list[str]:
    schema, _ = dereference(raw_schema, source)
    types = tuple(value for value in accepted_types(schema) if value != "null")
    if types != ("array",):
        return [
            f"{indent}{name} = "
            f"{strict_read_expression(name, raw_schema, source, required)};"
        ]

    items = require_object(schema.get("items"), f"{name}.items")
    item_type = boxed(java_type(items, source))
    item_expression = strict_read_expression(
        f"{name}[]", items, source, True
    )
    lines = [
        f"{indent}if (!parser.hasToken(JsonToken.START_ARRAY)) {{",
        f"{indent}    return context.reportInputMismatch(",
        f"{indent}            {target_class}.class,",
        f'{indent}            "{name} must be encoded as a JSON array");',
        f"{indent}}}",
        f"{indent}ArrayList<{item_type}> {name}Values = new ArrayList<>();",
        f"{indent}while (parser.nextToken() != JsonToken.END_ARRAY) {{",
    ]
    if "maxItems" in schema:
        lines.extend(
            [
                f"{indent}    if ({name}Values.size() >= {schema['maxItems']}) {{",
                f"{indent}        return context.reportInputMismatch(",
                f"{indent}                {target_class}.class,",
                f'{indent}                "{name} exceeds its canonical item limit");',
                f"{indent}    }}",
            ]
        )
    lines.extend(
        [
            f"{indent}    {name}Values.add({item_expression});",
            f"{indent}}}",
            f"{indent}{name} = List.copyOf({name}Values);",
        ]
    )
    return lines


def render_strict_deserializer(
    class_name: str,
    schema: dict[str, Any],
    source: Source,
    declaration_indent: str,
) -> list[str]:
    properties = require_object(schema.get("properties"), f"{class_name}.properties")
    required_value = schema.get("required", [])
    if (
        not isinstance(required_value, list)
        or any(not isinstance(name, str) for name in required_value)
        or len(required_value) != len(set(required_value))
        or set(required_value) != set(properties)
    ):
        raise GenerationFailure(
            f"{class_name} required fields must exactly close properties"
        )
    required = set(required_value)
    indent = declaration_indent + "    "
    body = indent + "    "
    switch_body = body + "        "
    assignment_indent = switch_body + "    "
    lines = [
        "",
        f"{indent}public static final class Deserializer",
        f"{indent}        extends JsonDeserializer<{class_name}> {{",
        "",
        f"{body}@Override",
        f"{body}public {class_name} deserialize(",
        f"{body}        JsonParser parser,",
        f"{body}        DeserializationContext context) throws IOException {{",
        f"{body}    if (!parser.isExpectedStartObjectToken()) {{",
        f"{body}        return context.reportInputMismatch(",
        f"{body}                {class_name}.class,",
        f'{body}                "{class_name} must be encoded as a JSON object");',
        f"{body}    }}",
    ]
    for name, child in properties.items():
        child_schema = require_object(child, name)
        field_type = java_type(child_schema, source, name in required)
        lines.append(
            f"{body}    {field_type} {name} = {java_default(field_type)}; "
            f"boolean {name}Seen = false;"
        )
    lines.extend(
        [
            f"{body}    while (parser.nextToken() != JsonToken.END_OBJECT) {{",
            f"{body}        if (!parser.hasToken(JsonToken.FIELD_NAME)) {{",
            f"{body}            return context.reportInputMismatch(",
            f"{body}                    {class_name}.class,",
            f'{body}                    "{class_name} contains a non-field token");',
            f"{body}        }}",
            f"{body}        String propertyName = parser.currentName();",
            f"{body}        if (parser.nextToken() == null) {{",
            f"{body}            return context.reportInputMismatch(",
            f"{body}                    {class_name}.class,",
            f'{body}                    "{class_name} ends before a property value");',
            f"{body}        }}",
            f"{body}        switch (propertyName) {{",
        ]
    )
    for name, child in properties.items():
        child_schema = require_object(child, name)
        lines.extend(
            [
                f'{switch_body}case "{name}" -> {{',
                f"{assignment_indent}if ({name}Seen) {{",
                f"{assignment_indent}    return context.reportInputMismatch(",
                f"{assignment_indent}            {class_name}.class,",
                f'{assignment_indent}            "duplicate property {name}");',
                f"{assignment_indent}}}",
                f"{assignment_indent}{name}Seen = true;",
            ]
        )
        lines.extend(
            strict_assignment_lines(
                name,
                child_schema,
                source,
                name in required,
                class_name,
                assignment_indent,
            )
        )
        lines.append(f"{switch_body}}}")
    lines.extend(
        [
            f"{switch_body}default -> {{",
            f"{assignment_indent}return context.reportInputMismatch(",
            f"{assignment_indent}        {class_name}.class,",
            f'{assignment_indent}        "unknown property %s", propertyName);',
            f"{switch_body}}}",
            f"{body}        }}",
            f"{body}    }}",
        ]
    )
    for name in properties:
        if name not in required:
            continue
        lines.extend(
            [
                f"{body}    if (!{name}Seen) {{",
                f"{body}        return context.reportInputMismatch({class_name}.class,",
                f'{body}                "missing required property {name}");',
                f"{body}    }}",
            ]
        )
    arguments = list(properties)
    lines.append(f"{body}    return new {class_name}(")
    for index, name in enumerate(arguments):
        suffix = "," if index + 1 < len(arguments) else ");"
        lines.append(f"{body}            {name}{suffix}")
    lines.extend(
        [
            f"{body}}}",
            f"{indent}}}",
        ]
    )
    return lines


def render_record(
    class_name: str,
    schema_key: str,
    schema: dict[str, Any],
    source: Source,
    declaration_indent: str,
    public: bool,
) -> list[str]:
    properties = require_object(schema.get("properties"), f"{class_name}.properties")
    required_value = schema.get("required", [])
    if not isinstance(required_value, list):
        raise GenerationFailure(f"{class_name}.required must be an array")
    required = set(required_value)
    components: list[str] = []
    for name, child in properties.items():
        child_schema = require_object(child, name)
        rendered_type = java_type(child_schema, source, name in required)
        value_type = decimal_value_type(child_schema, source)
        resolved, _ = dereference(child_schema, source)
        annotations = ""
        if value_type is not None:
            deserializer = DECIMAL_DESERIALIZERS[value_type]
            annotations = (
                "@JsonSerialize(using = CanonicalDecimalJson.Serializer.class) "
                f"@JsonDeserialize(using = CanonicalDecimalJson.{deserializer}.class) "
            )
        elif resolved.get("format") in CANONICAL_SCALAR_SERIALIZERS:
            serializer = CANONICAL_SCALAR_SERIALIZERS[resolved["format"]]
            annotations = (
                f"@JsonSerialize(using = CanonicalHrisJson.{serializer}.class) "
            )
        elif "array" in accepted_types(resolved):
            item_schema = require_object(resolved.get("items"), f"{name}.items")
            item_resolved, _ = dereference(item_schema, source)
            if item_resolved.get("format") in CANONICAL_SCALAR_SERIALIZERS:
                serializer = CANONICAL_SCALAR_SERIALIZERS[item_resolved["format"]]
                annotations = (
                    "@JsonSerialize(contentUsing = "
                    f"CanonicalHrisJson.{serializer}.class) "
                )
        components.append(
            f"{declaration_indent}        {annotations}{rendered_type} {name}"
        )
    modifier = "public " if public else "public "
    lines = [
        f"{declaration_indent}@JsonInclude(JsonInclude.Include.ALWAYS)",
        f"{declaration_indent}@JsonDeserialize(using = {class_name}.Deserializer.class)",
        f"{declaration_indent}{modifier}record {class_name}(",
    ]
    for index, component in enumerate(components):
        suffix = "," if index + 1 < len(components) else ") {"
        lines.append(component + suffix)
    lines.append("")
    lines.append(f"{declaration_indent}    public {class_name} {{")
    for name, child in properties.items():
        validations = validation_lines(
            name,
            require_object(child, name),
            source,
            name in required,
            declaration_indent + "        ",
        )
        lines.extend(validations)
    lines.extend(
        conditional_validation_lines(
            schema, source, class_name, declaration_indent + "        "
        )
    )
    lines.extend(
        extension_validation_lines(
            schema, source, schema_key, declaration_indent + "        "
        )
    )
    lines.extend(
        local_invariant_validation_lines(
            source.source_id, schema_key, declaration_indent + "        "
        )
    )
    lines.append(f"{declaration_indent}    }}")
    lines.extend(
        render_strict_deserializer(
            class_name,
            schema,
            source,
            declaration_indent,
        )
    )
    lines.append(f"{declaration_indent}}}")
    return lines


def render_strict_json_codec() -> str:
    return "\n".join(
        [
            "/*",
            " * Generated by scripts/generate-hris-contracts.py --write.",
            " * Shared strict token readers for canonical HRIS record deserializers.",
            " * Do not edit by hand.",
            " */",
            "package com.dwp.platform.contracts.hris.generated;",
            "",
            "import com.fasterxml.jackson.core.JsonGenerator;",
            "import com.fasterxml.jackson.core.JsonParser;",
            "import com.fasterxml.jackson.core.JsonToken;",
            "import com.fasterxml.jackson.databind.DeserializationContext;",
            "import com.fasterxml.jackson.databind.JsonSerializer;",
            "import com.fasterxml.jackson.databind.SerializerProvider;",
            "",
            "import java.io.IOException;",
            "import java.math.BigDecimal;",
            "import java.time.Instant;",
            "import java.time.LocalDate;",
            "import java.time.format.DateTimeParseException;",
            "import java.util.UUID;",
            "import java.util.regex.Pattern;",
            "",
            "/** Non-coercing JSON token readers used by every generated HRIS wire type. */",
            "public final class CanonicalHrisJson {",
            "",
            "    private static final Pattern UUID_PATTERN = Pattern.compile(",
            '            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");',
            "",
            "    private CanonicalHrisJson() {",
            "    }",
            "",
            "    public static final class UuidSerializer extends JsonSerializer<UUID> {",
            "        @Override",
            "        public void serialize(",
            "                UUID value,",
            "                JsonGenerator generator,",
            "                SerializerProvider serializers) throws IOException {",
            "            generator.writeString(value.toString());",
            "        }",
            "    }",
            "",
            "    public static final class LocalDateSerializer extends JsonSerializer<LocalDate> {",
            "        @Override",
            "        public void serialize(",
            "                LocalDate value,",
            "                JsonGenerator generator,",
            "                SerializerProvider serializers) throws IOException {",
            "            generator.writeString(value.toString());",
            "        }",
            "    }",
            "",
            "    public static final class InstantSerializer extends JsonSerializer<Instant> {",
            "        @Override",
            "        public void serialize(",
            "                Instant value,",
            "                JsonGenerator generator,",
            "                SerializerProvider serializers) throws IOException {",
            "            generator.writeString(value.toString());",
            "        }",
            "    }",
            "",
            "    public static String readString(",
            "            JsonParser parser,",
            "            DeserializationContext context,",
            "            String name,",
            "            boolean nullable) throws IOException {",
            "        if (parser.hasToken(JsonToken.VALUE_NULL) && nullable) {",
            "            return null;",
            "        }",
            "        if (!parser.hasToken(JsonToken.VALUE_STRING)) {",
            "            return context.reportInputMismatch(",
            "                    String.class,",
            '                    "%s must be encoded as a JSON string", name);',
            "        }",
            "        return parser.getText();",
            "    }",
            "",
            "    public static UUID readUuid(",
            "            JsonParser parser,",
            "            DeserializationContext context,",
            "            String name,",
            "            boolean nullable) throws IOException {",
            "        String raw = readString(parser, context, name, nullable);",
            "        if (raw == null) {",
            "            return null;",
            "        }",
            "        if (!UUID_PATTERN.matcher(raw).matches()) {",
            "            return context.reportInputMismatch(",
            "                    UUID.class,",
            '                    "%s is not a canonical UUID string", name);',
            "        }",
            "        try {",
            "            return UUID.fromString(raw);",
            "        } catch (IllegalArgumentException exception) {",
            "            return context.reportInputMismatch(",
            "                    UUID.class,",
            '                    "%s is not a valid UUID", name);',
            "        }",
            "    }",
            "",
            "    public static LocalDate readDate(",
            "            JsonParser parser,",
            "            DeserializationContext context,",
            "            String name,",
            "            boolean nullable) throws IOException {",
            "        String raw = readString(parser, context, name, nullable);",
            "        if (raw == null) {",
            "            return null;",
            "        }",
            "        try {",
            "            return LocalDate.parse(raw);",
            "        } catch (DateTimeParseException exception) {",
            "            return context.reportInputMismatch(",
            "                    LocalDate.class,",
            '                    "%s is not a valid ISO date", name);',
            "        }",
            "    }",
            "",
            "    public static Instant readInstant(",
            "            JsonParser parser,",
            "            DeserializationContext context,",
            "            String name,",
            "            boolean nullable) throws IOException {",
            "        String raw = readString(parser, context, name, nullable);",
            "        if (raw == null) {",
            "            return null;",
            "        }",
            "        try {",
            "            return Instant.parse(raw);",
            "        } catch (DateTimeParseException exception) {",
            "            return context.reportInputMismatch(",
            "                    Instant.class,",
            '                    "%s is not a valid ISO instant", name);',
            "        }",
            "    }",
            "",
            "    public static Integer readInt(",
            "            JsonParser parser,",
            "            DeserializationContext context,",
            "            String name,",
            "            boolean nullable) throws IOException {",
            "        if (parser.hasToken(JsonToken.VALUE_NULL) && nullable) {",
            "            return null;",
            "        }",
            "        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {",
            "            return context.reportInputMismatch(",
            "                    Integer.class,",
            '                    "%s must be encoded as a JSON integer", name);',
            "        }",
            "        return parser.getIntValue();",
            "    }",
            "",
            "    public static Long readLong(",
            "            JsonParser parser,",
            "            DeserializationContext context,",
            "            String name,",
            "            boolean nullable) throws IOException {",
            "        if (parser.hasToken(JsonToken.VALUE_NULL) && nullable) {",
            "            return null;",
            "        }",
            "        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {",
            "            return context.reportInputMismatch(",
            "                    Long.class,",
            '                    "%s must be encoded as a JSON integer", name);',
            "        }",
            "        return parser.getLongValue();",
            "    }",
            "",
            "    public static BigDecimal readNumber(",
            "            JsonParser parser,",
            "            DeserializationContext context,",
            "            String name,",
            "            boolean nullable) throws IOException {",
            "        if (parser.hasToken(JsonToken.VALUE_NULL) && nullable) {",
            "            return null;",
            "        }",
            "        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)",
            "                && !parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {",
            "            return context.reportInputMismatch(",
            "                    BigDecimal.class,",
            '                    "%s must be encoded as a JSON number", name);',
            "        }",
            "        return parser.getDecimalValue();",
            "    }",
            "",
            "    public static Boolean readBoolean(",
            "            JsonParser parser,",
            "            DeserializationContext context,",
            "            String name,",
            "            boolean nullable) throws IOException {",
            "        if (parser.hasToken(JsonToken.VALUE_NULL) && nullable) {",
            "            return null;",
            "        }",
            "        if (!parser.hasToken(JsonToken.VALUE_TRUE)",
            "                && !parser.hasToken(JsonToken.VALUE_FALSE)) {",
            "            return context.reportInputMismatch(",
            "                    Boolean.class,",
            '                    "%s must be encoded as a JSON boolean", name);',
            "        }",
            "        return parser.getBooleanValue();",
            "    }",
            "",
            "    public static <T> T readObject(",
            "            JsonParser parser,",
            "            DeserializationContext context,",
            "            String name,",
            "            Class<T> type,",
            "            boolean nullable) throws IOException {",
            "        if (parser.hasToken(JsonToken.VALUE_NULL) && nullable) {",
            "            return null;",
            "        }",
            "        if (!parser.hasToken(JsonToken.START_OBJECT)) {",
            "            return context.reportInputMismatch(",
            "                    type,",
            '                    "%s must be encoded as a JSON object", name);',
            "        }",
            "        return context.readValue(parser, type);",
            "    }",
            "}",
            "",
        ]
    )


def render_decimal_codec(sources: list[Source]) -> str:
    try:
        source = next(item for item in sources if item.source_id == "xcon-v1")
    except StopIteration as exception:
        raise GenerationFailure("xcon-v1 source is required for decimal codecs") from exception
    definitions = require_object(source.document.get("$defs"), "xcon-v1.$defs")
    definition_names = {
        "InputAmount": "InputAmount",
        "QuantityDecimal": "QuantityDecimal",
        "PostedMoney": "PostedMoneyAmount",
    }
    patterns: dict[str, str] = {}
    for value_type, definition_name in definition_names.items():
        schema = require_object(definitions.get(definition_name), definition_name)
        if decimal_value_type(schema, source) != value_type:
            raise GenerationFailure(
                f"{definition_name} does not bind canonical decimal type {value_type}"
            )
        patterns[value_type] = schema["pattern"]

    return "\n".join(
        [
            "/*",
            " * Generated by scripts/generate-hris-contracts.py --write.",
            " * Source: contracts/hris/canonical/schemas/cross-module-canonical-schemas.v1.json decimal definitions",
            " * Do not edit by hand.",
            " */",
            "package com.dwp.platform.contracts.hris.generated;",
            "",
            "import com.fasterxml.jackson.core.JsonGenerator;",
            "import com.fasterxml.jackson.core.JsonParser;",
            "import com.fasterxml.jackson.core.JsonToken;",
            "import com.fasterxml.jackson.databind.DeserializationContext;",
            "import com.fasterxml.jackson.databind.JsonDeserializer;",
            "import com.fasterxml.jackson.databind.JsonSerializer;",
            "import com.fasterxml.jackson.databind.SerializerProvider;",
            "",
            "import java.io.IOException;",
            "import java.math.BigDecimal;",
            "import java.util.Objects;",
            "import java.util.regex.Pattern;",
            "",
            "/** Strict canonical JSON-string codecs for shared HRIS decimal values. */",
            "public final class CanonicalDecimalJson {",
            "",
            f"    private static final Pattern INPUT_AMOUNT = Pattern.compile({java_string(patterns['InputAmount'])});",
            f"    private static final Pattern QUANTITY_DECIMAL = Pattern.compile({java_string(patterns['QuantityDecimal'])});",
            f"    private static final Pattern POSTED_MONEY = Pattern.compile({java_string(patterns['PostedMoney'])});",
            "",
            "    private CanonicalDecimalJson() {",
            "    }",
            "",
            "    public static BigDecimal normalize(BigDecimal value) {",
            '        Objects.requireNonNull(value, "decimal value must not be null");',
            "        if (value.signum() == 0) {",
            "            return BigDecimal.ZERO;",
            "        }",
            "        return value.stripTrailingZeros();",
            "    }",
            "",
            "    public static String canonicalString(BigDecimal value) {",
            "        return normalize(value).toPlainString();",
            "    }",
            "",
            "    public static final class Serializer extends JsonSerializer<BigDecimal> {",
            "        @Override",
            "        public void serialize(",
            "                BigDecimal value,",
            "                JsonGenerator generator,",
            "                SerializerProvider serializers) throws IOException {",
            "            generator.writeString(canonicalString(value));",
            "        }",
            "    }",
            "",
            "    private abstract static class StrictDeserializer",
            "            extends JsonDeserializer<BigDecimal> {",
            "",
            "        private final String valueType;",
            "        private final Pattern pattern;",
            "",
            "        private StrictDeserializer(String valueType, Pattern pattern) {",
            "            this.valueType = valueType;",
            "            this.pattern = pattern;",
            "        }",
            "",
            "        @Override",
            "        public final BigDecimal deserialize(",
            "                JsonParser parser,",
            "                DeserializationContext context) throws IOException {",
            "            if (!parser.hasToken(JsonToken.VALUE_STRING)) {",
            "                return context.reportInputMismatch(",
            "                        BigDecimal.class,",
            '                        "%s must be encoded as a JSON string",',
            "                        valueType);",
            "            }",
            "            String raw = parser.getText();",
            "            if (!pattern.matcher(raw).matches()) {",
            "                return context.reportInputMismatch(",
            "                        BigDecimal.class,",
            '                        "%s is not a canonical decimal string",',
            "                        valueType);",
            "            }",
            "            try {",
            "                return normalize(new BigDecimal(raw));",
            "            } catch (NumberFormatException exception) {",
            "                return context.reportInputMismatch(",
            "                        BigDecimal.class,",
            '                        "%s cannot be parsed as a decimal",',
            "                        valueType);",
            "            }",
            "        }",
            "    }",
            "",
            "    public static final class InputAmountDeserializer extends StrictDeserializer {",
            "        public InputAmountDeserializer() {",
            '            super("InputAmount", INPUT_AMOUNT);',
            "        }",
            "    }",
            "",
            "    public static final class QuantityDecimalDeserializer extends StrictDeserializer {",
            "        public QuantityDecimalDeserializer() {",
            '            super("QuantityDecimal", QUANTITY_DECIMAL);',
            "        }",
            "    }",
            "",
            "    public static final class PostedMoneyDeserializer extends StrictDeserializer {",
            "        public PostedMoneyDeserializer() {",
            '            super("PostedMoney", POSTED_MONEY);',
            "        }",
            "    }",
            "}",
            "",
        ]
    )


def render_contract(contract: Contract, root: Path = ROOT) -> str:
    object_names = referenced_object_definitions(contract.schema, contract.source)
    imports = collect_imports(contract, object_names)
    source_relative = (
        contract.source.schema_path.relative_to(root).as_posix()
        if contract.source.schema_path.is_relative_to(root)
        else contract.source.schema_path.name
    )
    lines = [
        "/*",
        " * Generated by scripts/generate-hris-contracts.py --write.",
        f" * Source: {source_relative}#/$defs/{contract.definition}",
        f" * Normalized schema SHA-256: {contract.normalized_digest}",
        " * Do not edit by hand.",
        " */",
        f"package {contract.package_name};",
        "",
    ]
    lines.extend(f"import {name};" for name in imports)
    lines.extend(
        [
            "",
            "/** Immutable Java binding for the canonical HRIS schema. */",
        ]
    )
    top = render_record(
        contract.class_name,
        contract.definition,
        contract.schema,
        contract.source,
        "",
        True,
    )
    # Replace the top-level closing brace so nested schema records remain scoped
    # to this one generated class and cannot collide with another contract.
    closing = top.pop()
    lines.extend(top)
    for object_name in object_names:
        lines.append("")
        lines.extend(
            render_record(
                object_name,
                object_name,
                require_object(contract.source.document["$defs"][object_name], object_name),
                contract.source,
                "    ",
                True,
            )
        )
    lines.append(closing)
    lines.append("")
    return "\n".join(lines)


def expected_outputs(root: Path = ROOT) -> dict[Path, str]:
    sources, contracts = load_contracts(root)
    outputs: dict[Path, str] = {}
    for contract in contracts:
        package_path = Path(*contract.package_name.split("."))
        relative = JAVA_OUTPUT_ROOT / package_path / f"{contract.class_name}.java"
        outputs[relative] = render_contract(contract, root)
    outputs[DECIMAL_CODEC_PATH] = render_decimal_codec(sources)
    outputs[STRICT_JSON_CODEC_PATH] = render_strict_json_codec()
    return dict(sorted(outputs.items(), key=lambda item: item[0].as_posix()))


def governed_outputs(root: Path, sources: list[Source]) -> set[Path]:
    result: set[Path] = set()
    for source in sources:
        directory = root / JAVA_OUTPUT_ROOT / Path(*source.java_package.split("."))
        if directory.exists():
            result.update(path.relative_to(root) for path in directory.glob("*.java"))
    return result


def check_outputs(root: Path = ROOT) -> list[str]:
    sources, _ = load_contracts(root)
    expected = expected_outputs(root)
    actual = governed_outputs(root, sources)
    violations: list[str] = []
    expected_paths = set(expected)
    for relative in sorted(expected_paths - actual):
        violations.append(f"missing generated source: {relative.as_posix()}")
    for relative in sorted(actual - expected_paths):
        violations.append(f"extra generated source: {relative.as_posix()}")
    for relative in sorted(expected_paths & actual):
        try:
            content = (root / relative).read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exception:
            violations.append(f"cannot read generated source {relative}: {exception}")
            continue
        if content != expected[relative]:
            violations.append(f"generated source drift: {relative.as_posix()}")
    return violations


def write_outputs(root: Path = ROOT) -> int:
    sources, _ = load_contracts(root)
    expected = expected_outputs(root)
    actual = governed_outputs(root, sources)
    for relative in sorted(actual - set(expected)):
        (root / relative).unlink()
    for relative, content in expected.items():
        destination = root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists() and destination.read_text(encoding="utf-8") == content:
            continue
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            dir=destination.parent,
            prefix=f".{destination.name}.",
            delete=False,
        ) as handle:
            handle.write(content)
            temporary = Path(handle.name)
        os.replace(temporary, destination)
    print(
        f"Generated {len(expected)} deterministic HRIS Java sources "
        f"from {sum(source.expected_count for source in sources)} canonical mappings."
    )
    return 0


def main(arguments: list[str] | None = None, root: Path = ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="write canonical generated sources")
    mode.add_argument("--check", action="store_true", help="verify checked-in sources without writes")
    options = parser.parse_args(arguments)
    try:
        if options.write:
            return write_outputs(root)
        violations = check_outputs(root)
    except GenerationFailure as exception:
        print(f"HRIS contract generation failed: {exception}", file=sys.stderr)
        return 1
    if violations:
        print("HRIS generated contract verification failed:", file=sys.stderr)
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1
    sources, contracts = load_contracts(root)
    print(
        "PASS HRIS generated contracts: "
        f"{len(expected_outputs(root))} exact Java sources match "
        f"{len(contracts)} canonical mappings across {len(sources)} schemas."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
