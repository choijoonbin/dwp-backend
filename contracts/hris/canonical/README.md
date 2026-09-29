# Canonical HRIS Java contracts

This directory is the self-contained source of truth for the shared HRIS Java
contract artifact in `dwp-platform-contracts`. It intentionally has no runtime
or build-time dependency on the delivery blueprint.

- `schemas/` contains the byte-for-byte canonical JSON Schema documents.
- `hris-contract-codegen-manifest.v1.json` pins each schema file digest, records
  the source binding-register digest for provenance, and maps all 21 XCON plus
  11 platform dependency definitions to their exact Java imports. It also
  digests, owns, and classifies every schema `x-*` extension. Record-local
  rules are `GENERATED_CONSTRUCTOR`; lifecycle, transport, signature, and
  cross-record obligations are explicitly `DOMAIN_RUNTIME` rather than being
  silently discarded.
- `scripts/generate-hris-contracts.py --write` regenerates the Java records.
- `scripts/generate-hris-contracts.py --check` fails on schema, mapping, file,
  field, or generated-content drift and never writes files.

The generated records expose Java 21 platform value types. They are immutable,
defensively copy collection values, and reject missing non-null values plus
machine-readable scalar and collection constraints from the schemas. Every
record also owns a non-coercing Jackson deserializer: required nullable fields
must still be present, duplicate and additional properties are rejected, and
JSON token types are checked before construction. Required null-valued fields
are always serialized even when a host mapper uses `NON_NULL` inclusion.
Decimal record components retain the `BigDecimal` Java ABI but use the generated
strict Jackson codec: wire input must be a schema-valid JSON string, wire output
is a canonical non-exponent string, and signed or scaled zero is normalized to
`0`. UUID, date, and date-time components always serialize as JSON strings;
UUID input follows the schema `uuid` format's case-insensitive hexadecimal form
and canonical output is the lowercase representation returned by `UUID.toString()`.
The canonical directory is packaged into the contract JAR below
`META-INF/dwp/hris/canonical` so runtime owners can load the sealed policies.
