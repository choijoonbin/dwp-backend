from __future__ import annotations

import importlib.util
import copy
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).resolve().parents[1] / "check-service-boundaries.py"
SPEC = importlib.util.spec_from_file_location("service_boundary_checker", CHECKER)
assert SPEC is not None and SPEC.loader is not None
CHECKER_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER_MODULE)


class ApplicationLayerBoundaryTest(unittest.TestCase):

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.original_root = CHECKER_MODULE.ROOT
        CHECKER_MODULE.ROOT = self.root

    def tearDown(self) -> None:
        CHECKER_MODULE.ROOT = self.original_root
        self.temporary_directory.cleanup()

    def write_source(self, name: str, source: str) -> None:
        path = (
            self.root
            / "dwp-platform-server/src/main/java/com/dwp/services/platform/example"
            / name
        )
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")

    def test_controller_must_use_an_application_service_instead_of_repository(self) -> None:
        self.write_source(
            "OrderController.java",
            """package com.dwp.services.platform.example;
               final class OrderController {
                   private final OrderRepository repository;
               }
            """,
        )

        violations = CHECKER_MODULE.application_layer_violations()

        self.assertEqual(1, len(violations))
        self.assertIn("OrderRepository", violations[0])
        self.assertIn("application service", violations[0])

    def test_comments_and_strings_do_not_create_layer_dependencies(self) -> None:
        self.write_source(
            "OrderController.java",
            """package com.dwp.services.platform.example;
               final class OrderController {
                   // HistoricalOrderRepository was replaced.
                   private static final String NOTE = "Do not use OldOrderRepository";
                   private final OrderQueryService service;
               }
            """,
        )

        self.assertEqual([], CHECKER_MODULE.application_layer_violations())

    def test_external_workload_adapter_requires_explicit_origin_validation(self) -> None:
        relative = (
            "dwp-meeting-server/src/main/java/com/dwp/services/meeting/"
            "provider/MeetingWorkloadClient.java"
        )
        self.write_source("Unused.java", "package com.dwp.services.platform.example;")
        workload = self.root / relative
        workload.parent.mkdir(parents=True, exist_ok=True)
        workload.write_text("final class MeetingWorkloadClient {}", encoding="utf-8")
        policy = {
            "version": 2,
            "resilienceDefaults": {
                "connectTimeoutMs": 1_000,
                "readTimeoutMs": 5_000,
                "bulkheadMaxConcurrentCalls": 10,
                "maximumRetryAttempts": 1,
                "circuitBreaker": True,
            },
            "httpClients": [{
                "id": "meeting-workload",
                "classification": "governed-workload",
                "interfaceType": "external-connector",
                "sourceService": "dwp-meeting-server",
                "targetServices": ["dwp-agent"],
                "path": relative,
                "purpose": "Call a separately deployed governed workload.",
                "auth": "Signed workload assertion.",
                "retryMode": "none",
                "failureMode": "fail-closed",
                "requiredMarkers": ["validatedOrigin"],
                "forbiddenMarkers": ["X-DWP-Service-Token"],
            }],
            "crossDatabaseExceptions": [],
            "metadataScanners": [],
        }

        self.assertEqual([], CHECKER_MODULE.policy_manifest_violations(policy))


class SignedWorkloadBoundaryTest(unittest.TestCase):
    PACKAGE = "com.dwp.services.platform.example"
    SOURCE_ROOT = "dwp-platform-server/src/main/java/com/dwp/services/platform/example"
    CLIENT = f"{SOURCE_ROOT}/SourceClient.java"
    PROTOCOL = f"{SOURCE_ROOT}/SourceProtocol.java"
    SIGNER = f"{SOURCE_ROOT}/SourceSigner.java"

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.original_root = CHECKER_MODULE.ROOT
        self.original_policy_file = CHECKER_MODULE.POLICY_FILE
        CHECKER_MODULE.ROOT = self.root
        CHECKER_MODULE.POLICY_FILE = self.root / "docs/architecture/service-interface-contracts.json"
        self.sources = {
            self.PROTOCOL: f'''package {self.PACKAGE};
final class SourceProtocol {{
    static final String PATH = "/internal/v1/meeting-followups/resolve";
    static final String ASSERTION_HEADER = "X-DWP-Work-Assertion";
    static final String ISSUER = "dwp-platform-work";
    static final String AUDIENCE = "dwp-meeting-followup-source";
}}''',
            self.CLIENT: f'''package {self.PACKAGE};
import java.net.http.HttpClient;
import static {self.PACKAGE}.SourceProtocol.*;
final class SourceClient {{
    HttpRequest resolve(Request request) {{
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        SourceSigner signer = new SourceSigner();
        URI endpoint = base.resolve(PATH);
        byte[] body = mapper.writeValueAsBytes(request);
        OutboundHttpHeaders.propagateObservability(headers);
        return HttpRequest.newBuilder(endpoint)
            .header(ASSERTION_HEADER, signer.sign(request, body))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }}
}}''',
            self.SIGNER: f'''package {self.PACKAGE};
import static {self.PACKAGE}.SourceProtocol.*;
final class SourceSigner {{
    String sign(Request request, byte[] exactBody) {{
        if (secret.length < 32) throw new IllegalArgumentException();
        long issuedAt = clock.instant().getEpochSecond();
        Claims claims = new Claims(1, keyId, ISSUER, AUDIENCE, "POST", PATH,
            request.tenantId(), request.actorUserId(), request.source().meetingId(),
            request.source().reportId(), request.source().candidateId(), request.action(),
            issuedAt, issuedAt + 30, nonce.get(),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(exactBody)));
        String input = "dwp1." + BASE64.encodeToString(mapper.writeValueAsBytes(claims));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return input + "." + BASE64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }}
}}''',
        }
        for relative, source in self.sources.items():
            self.write(relative, source)
        self.entry = {
            "id": "platform-meeting-source", "classification": "source-verification",
            "interfaceType": "internal-http", "sourceService": "dwp-platform-server",
            "targetServices": ["dwp-meeting-server"], "path": self.CLIENT,
            "purpose": "Verify owner-confirmed Work terms without activation.",
            "auth": "Dedicated signed workload, not a product entitlement.",
            "retryMode": "none", "failureMode": "fail-closed",
            "requiredMarkers": ["OutboundHttpHeaders.propagateObservability"],
            "forbiddenMarkers": sorted(CHECKER_MODULE.SIGNED_WORKLOAD_FORBIDDEN),
            "signedWorkload": {
                "profile": "dwp1-hmac-sha256", "protocolSource": self.PROTOCOL,
                "signerSource": self.SIGNER, "method": "POST",
                "path": "/internal/v1/meeting-followups/resolve",
                "header": "X-DWP-Work-Assertion", "issuer": "dwp-platform-work",
                "audience": "dwp-meeting-followup-source", "ttlSeconds": 30,
            },
        }
        self.policy = {
            "version": 2,
            "resilienceDefaults": {
                "connectTimeoutMs": 1_000, "readTimeoutMs": 5_000,
                "bulkheadMaxConcurrentCalls": 10, "maximumRetryAttempts": 1,
                "circuitBreaker": True,
            },
            "httpClients": [self.entry], "crossDatabaseExceptions": [], "metadataScanners": [],
        }

    def tearDown(self) -> None:
        CHECKER_MODULE.ROOT = self.original_root
        CHECKER_MODULE.POLICY_FILE = self.original_policy_file
        self.directory.cleanup()

    def write(self, relative: str, source: str) -> None:
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")

    def test_accepts_explicit_signed_workload_with_linked_protocol_and_signer(self) -> None:
        self.assertEqual([], CHECKER_MODULE.policy_manifest_violations(self.policy))
        self.assertEqual([], CHECKER_MODULE.http_client_policy_violations(self.policy))

    def test_rejects_unknown_or_weakened_signed_workload_metadata(self) -> None:
        cases = [
            ("profile", "unsigned"), ("ttlSeconds", True), ("ttlSeconds", 0),
            ("ttlSeconds", 31), ("method", "GET"), ("path", "/api/meetings/v1/home"),
            ("path", "/internal/v1/../resolve"), ("path", "/internal/v1/resolve?actor=1"),
            ("header", "X-DWP-Service-Token"), ("issuer", "dwp-gateway"),
            ("audience", "dwp-platform-work"), ("skipSignatureCheck", True),
        ]
        for key, value in cases:
            with self.subTest(key=key, value=value):
                policy = copy.deepcopy(self.policy)
                policy["httpClients"][0]["signedWorkload"][key] = value
                self.assertTrue(CHECKER_MODULE.policy_manifest_violations(policy))

    def test_rejects_wrong_interface_retry_failure_and_token_exemptions(self) -> None:
        for key, value in [
            ("interfaceType", "external-connector"), ("retryMode", "idempotent-only"),
            ("failureMode", "fail-contained"), ("targetServices", ["dwp-platform-server"]),
            ("signedWorkload", None), ("forbiddenMarkers", ["/api/"]),
        ]:
            with self.subTest(key=key):
                policy = copy.deepcopy(self.policy)
                policy["httpClients"][0][key] = value
                self.assertTrue(CHECKER_MODULE.policy_manifest_violations(policy))

    def test_legacy_token_interfaces_cannot_use_missing_signed_metadata_as_an_exemption(self) -> None:
        self.entry.pop("signedWorkload")
        violations = CHECKER_MODULE.policy_manifest_violations(self.policy)
        self.assertTrue(any("purpose-specific service token" in value for value in violations))
        self.assertTrue(any("/internal/ path marker" in value for value in violations))
        self.entry["requiredMarkers"] += ["/internal/v1/", "X-DWP-Identity-Sync-Token"]
        self.assertEqual([], CHECKER_MODULE.policy_manifest_violations(self.policy))

    def test_rejects_source_path_traversal_other_owner_and_symlink_escape(self) -> None:
        other = self.root / "dwp-meeting-server/src/main/java/com/dwp/services/meeting/Other.java"
        other.parent.mkdir(parents=True)
        other.write_text("class Other {}", encoding="utf-8")
        link = self.root / self.SOURCE_ROOT / "Linked.java"
        link.symlink_to(other)
        for value in ["../Outside.java", str(other), str(other.relative_to(self.root)),
                      str(link.relative_to(self.root)), self.CLIENT]:
            with self.subTest(path=value):
                policy = copy.deepcopy(self.policy)
                policy["httpClients"][0]["signedWorkload"]["protocolSource"] = value
                self.assertTrue(CHECKER_MODULE.policy_manifest_violations(policy))

    def test_malformed_client_paths_report_violations_instead_of_crashing(self) -> None:
        build_file = self.root / "dwp-platform-server/build.gradle"
        build_file.write_text("plugins {}", encoding="utf-8")
        for value in (None, 123, [], "dwp-platform-server", "dwp-platform-server/Other.java",
                      "dwp-platform-server/build.gradle"):
            with self.subTest(path=value):
                policy = copy.deepcopy(self.policy)
                policy["httpClients"][0]["path"] = value
                self.assertTrue(CHECKER_MODULE.policy_manifest_violations(policy))

    def test_source_alias_outside_java_tree_is_not_accepted_by_resolved_location(self) -> None:
        alias = self.root / "dwp-platform-server/Protocol.java"
        alias.symlink_to(self.root / self.PROTOCOL)
        self.entry["signedWorkload"]["protocolSource"] = str(alias.relative_to(self.root))
        self.assertTrue(CHECKER_MODULE.policy_manifest_violations(self.policy))

    def test_protocol_literals_must_be_real_exact_declarations(self) -> None:
        for name in ("PATH", "ASSERTION_HEADER", "ISSUER", "AUDIENCE"):
            original = next(line for line in self.sources[self.PROTOCOL].splitlines()
                            if f"String {name} =" in line)
            for replacement in ["//" + original, f'String note = """\n{original}\n""";',
                                original.replace('";', '-changed";')]:
                with self.subTest(name=name, replacement=replacement):
                    self.write(self.PROTOCOL, self.sources[self.PROTOCOL].replace(original, replacement))
                    violations = CHECKER_MODULE.http_client_policy_violations(self.policy)
                    self.assertTrue(any(f"protocol {name}" in value for value in violations))
        self.write(self.PROTOCOL, self.sources[self.PROTOCOL])

    def test_registered_client_cannot_skip_validation_by_hiding_or_removing_http_import(self) -> None:
        original = "import java.net.http.HttpClient;"
        for replacement in ["", "//" + original, f'String note = "{original}";']:
            with self.subTest(replacement=replacement):
                self.write(self.CLIENT, self.sources[self.CLIENT].replace(original, replacement))
                violations = CHECKER_MODULE.http_client_policy_violations(self.policy)
                self.assertTrue(any("executable HTTP client import" in value for value in violations))

    def test_rejects_unwired_path_signature_posted_bytes_or_trace(self) -> None:
        cases = [
            ("base.resolve(PATH)", "base.resolve(otherPath)"),
            ("HttpRequest.newBuilder(endpoint)", "HttpRequest.newBuilder(otherEndpoint)"),
            ("SourceProtocol.*", "UnrelatedProtocol.*"),
            ("new SourceSigner()", "new OtherSigner()"),
            ("signer.sign(request, body)", "cachedAssertion"),
            ("ofByteArray(body)", "ofByteArray(otherBody)"),
            ("OutboundHttpHeaders.propagateObservability(headers);",
             "// OutboundHttpHeaders.propagateObservability(headers);"),
            ("package com.dwp.services.platform.example;", "package other;"),
        ]
        for original, replacement in cases:
            with self.subTest(original=original):
                self.write(self.CLIENT, self.sources[self.CLIENT].replace(original, replacement))
                self.assertTrue(CHECKER_MODULE.http_client_policy_violations(self.policy))

    def test_no_redirect_policy_cannot_be_replaced_by_a_string_marker(self) -> None:
        self.write(self.CLIENT, self.sources[self.CLIENT].replace("HttpClient.Redirect.NEVER", "HttpClient.Redirect.ALWAYS")
                   + '\nString note = "HttpClient.Redirect.NEVER";')
        self.assertTrue(CHECKER_MODULE.http_client_policy_violations(self.policy))

    def test_signer_comments_and_strings_cannot_replace_executable_security_checks(self) -> None:
        lines = [line for line in self.sources[self.SIGNER].splitlines() if any(
            marker in line for marker in ("Mac mac =", "mac.init(", "MessageDigest.getInstance",
                                         "String input =", "secret.length <", "issuedAt +")
        )]
        for original in lines:
            for replacement in ["//" + original, f'String note = """\n{original}\n""";']:
                with self.subTest(original=original, replacement=replacement):
                    self.write(self.SIGNER, self.sources[self.SIGNER].replace(original, replacement))
                    self.assertTrue(CHECKER_MODULE.http_client_policy_violations(self.policy))

    def test_signer_binds_the_actual_request_not_a_different_actor_or_action(self) -> None:
        for original in ("request.tenantId()", "request.actorUserId()", "request.source().candidateId()",
                         "request.action()", "digest(exactBody)", "issuedAt + 30", "nonce.get()"):
            with self.subTest(original=original):
                self.write(self.SIGNER, self.sources[self.SIGNER].replace(original, "differentValue"))
                self.assertTrue(CHECKER_MODULE.http_client_policy_violations(self.policy))

    def test_declared_ttl_cannot_be_extended_inside_the_signed_claim(self) -> None:
        for value in ("issuedAt + 30 + 3600", "issuedAt + 30 * 1000", "issuedAt + 300"):
            with self.subTest(expiry=value):
                self.write(self.SIGNER, self.sources[self.SIGNER].replace("issuedAt + 30", value)
                           + "\nlong decoy = issuedAt + 30;")
                self.assertTrue(CHECKER_MODULE.http_client_policy_violations(self.policy))

    def test_forbids_borrowed_gateway_or_service_tokens_in_supporting_sources(self) -> None:
        for relative in (self.CLIENT, self.PROTOCOL, self.SIGNER):
            with self.subTest(relative=relative):
                self.write(relative, self.sources[relative] + '\nString header = "X-DWP-Service-Token";')
                self.assertTrue(CHECKER_MODULE.http_client_policy_violations(self.policy))
                self.write(relative, self.sources[relative])


if __name__ == "__main__":
    unittest.main()
