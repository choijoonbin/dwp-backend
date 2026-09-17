from __future__ import annotations

import importlib.util
import copy
import json
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


class MeetingOwnerTokenBoundaryTest(unittest.TestCase):
    """Pin Meeting's two owner contracts without allowing other Meeting clients."""

    CONTRACT_IDS = (
        "meeting-notification-invitation-intent",
        "meeting-auth-followup-current-authority",
    )
    OWNER_TOKEN_FIELDS = {
        "profile", "method", "path", "header", "identityHeader", "identity",
        "securitySource", "endpointSource",
    }

    def setUp(self) -> None:
        import json

        self.original_root = CHECKER_MODULE.ROOT
        self.original_policy_file = CHECKER_MODULE.POLICY_FILE
        real_policy = json.loads(self.original_policy_file.read_text(encoding="utf-8"))
        self.policy = copy.deepcopy(real_policy)
        self.policy["httpClients"] = [
            entry for entry in self.policy["httpClients"] if entry["id"] in self.CONTRACT_IDS
        ]
        self.assertEqual(set(self.CONTRACT_IDS), {
            entry["id"] for entry in self.policy["httpClients"]
        })
        self.policy["crossDatabaseExceptions"] = []
        self.policy["metadataScanners"] = []
        self.entries = {entry["id"]: entry for entry in self.policy["httpClients"]}
        self.sources = {}
        for entry in self.policy["httpClients"]:
            for relative in (entry["path"], entry["ownerToken"]["securitySource"],
                             entry["ownerToken"]["endpointSource"]):
                self.sources[relative] = (self.original_root / relative).read_text(encoding="utf-8")
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        for relative, source in self.sources.items():
            self.write(relative, source)
        CHECKER_MODULE.ROOT = self.root
        CHECKER_MODULE.POLICY_FILE = self.root / "docs/architecture/service-interface-contracts.json"
        CHECKER_MODULE.POLICY_FILE.parent.mkdir(parents=True, exist_ok=True)
        CHECKER_MODULE.POLICY_FILE.write_text(json.dumps(self.policy), encoding="utf-8")

    def tearDown(self) -> None:
        CHECKER_MODULE.ROOT = self.original_root
        CHECKER_MODULE.POLICY_FILE = self.original_policy_file
        self.directory.cleanup()

    def write(self, relative: str, source: str) -> None:
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")

    def assert_source_mutation_rejected(self, relative: str, original: str,
                                        replacement: str) -> None:
        source = self.sources[relative]
        self.assertIn(original, source, "The mutation must exercise real executable source.")
        self.write(relative, source.replace(original, replacement))
        try:
            self.assertTrue(CHECKER_MODULE.http_client_policy_violations(self.policy))
        finally:
            self.write(relative, source)

    def test_accepts_only_exact_registered_owner_contracts_and_real_sources(self) -> None:
        for entry in self.policy["httpClients"]:
            self.assertEqual(self.OWNER_TOKEN_FIELDS, set(entry["ownerToken"]))
        self.assertEqual([], CHECKER_MODULE.policy_manifest_violations(self.policy))
        self.assertEqual([], CHECKER_MODULE.http_client_policy_violations(self.policy))
        loaded, violations = CHECKER_MODULE.load_policy()
        self.assertEqual([], violations)
        self.assertEqual(self.policy, loaded)

    def test_removing_registration_does_not_exempt_either_meeting_http_client(self) -> None:
        for contract_id in self.CONTRACT_IDS:
            with self.subTest(contract_id=contract_id):
                policy = copy.deepcopy(self.policy)
                policy["httpClients"] = [
                    entry for entry in policy["httpClients"] if entry["id"] != contract_id
                ]
                violations = CHECKER_MODULE.http_client_policy_violations(policy)
                self.assertTrue(any(self.entries[contract_id]["path"] in value
                                    and "not in" in value for value in violations))

    def test_an_additional_meeting_http_client_has_no_wildcard_allowance(self) -> None:
        relative = (
            "dwp-meeting-server/src/main/java/com/dwp/services/meeting/"
            "videomeeting/provider/UnregisteredMeetingClient.java"
        )
        self.write(relative, """package com.dwp.services.meeting.videomeeting.provider;
import java.net.http.HttpClient;
final class UnregisteredMeetingClient {
    private final HttpClient client = HttpClient.newHttpClient();
}
""")
        violations = CHECKER_MODULE.http_client_policy_violations(self.policy)
        self.assertTrue(any(relative in value and "not in" in value for value in violations))

    def test_owner_token_schema_rejects_missing_unknown_and_borrowed_values(self) -> None:
        cases = [
            ("profile", "purpose-token"), ("method", "GET"),
            ("path", "/internal/v1/../intents/direct"),
            ("path", "/internal/v1/intents/direct?tenant=1"),
            ("header", "X-DWP-Product-Surface-Token"),
            ("identityHeader", "X-DWP-User-ID"), ("identity", "dwp-gateway"),
            ("skipOwnerValidation", True),
        ]
        for contract_id in self.CONTRACT_IDS:
            for key in sorted(self.OWNER_TOKEN_FIELDS):
                with self.subTest(contract_id=contract_id, missing=key):
                    policy = copy.deepcopy(self.policy)
                    entry = next(entry for entry in policy["httpClients"]
                                 if entry["id"] == contract_id)
                    del entry["ownerToken"][key]
                    self.assertTrue(CHECKER_MODULE.policy_manifest_violations(policy))
            for key, value in cases:
                with self.subTest(contract_id=contract_id, key=key, value=value):
                    policy = copy.deepcopy(self.policy)
                    entry = next(entry for entry in policy["httpClients"]
                                 if entry["id"] == contract_id)
                    entry["ownerToken"][key] = value
                    self.assertTrue(CHECKER_MODULE.policy_manifest_violations(policy))
            other_id = next(value for value in self.CONTRACT_IDS if value != contract_id)
            policy = copy.deepcopy(self.policy)
            entry = next(entry for entry in policy["httpClients"] if entry["id"] == contract_id)
            entry["ownerToken"] = copy.deepcopy(self.entries[other_id]["ownerToken"])
            with self.subTest(contract_id=contract_id, borrowed_profile=other_id):
                self.assertTrue(CHECKER_MODULE.policy_manifest_violations(policy))

    def test_contract_owner_interface_retry_and_failure_cannot_be_weakened(self) -> None:
        for contract_id in self.CONTRACT_IDS:
            entry = self.entries[contract_id]
            cases = [
                ("sourceService", "dwp-platform-server"),
                ("targetServices", ["dwp-platform-server"]),
                ("targetServices", entry["targetServices"] + ["dwp-platform-server"]),
                ("interfaceType", "external-connector"),
                ("retryMode", "none" if entry["retryMode"] == "outbox-owned" else "outbox-owned"),
                ("failureMode", "fail-closed" if entry["failureMode"] == "fail-contained"
                 else "fail-contained"), ("ownerToken", None),
            ]
            for key, value in cases:
                with self.subTest(contract_id=contract_id, key=key, value=value):
                    policy = copy.deepcopy(self.policy)
                    changed = next(entry for entry in policy["httpClients"]
                                   if entry["id"] == contract_id)
                    changed[key] = value
                    self.assertTrue(CHECKER_MODULE.policy_manifest_violations(policy))
            policy = copy.deepcopy(self.policy)
            changed = next(entry for entry in policy["httpClients"] if entry["id"] == contract_id)
            del changed["ownerToken"]
            with self.subTest(contract_id=contract_id, missing="ownerToken"):
                self.assertTrue(CHECKER_MODULE.policy_manifest_violations(policy))

    def test_client_literals_and_endpoint_credential_identity_wiring_are_exact(self) -> None:
        for entry in self.policy["httpClients"]:
            relative = entry["path"]
            owner = entry["ownerToken"]
            cases = [(f'"{owner[key]}"', '"changed"')
                     for key in ("path", "header", "identityHeader", "identity")]
            cases += [
                ("origin(properties).resolve(PATH)", "origin(properties).resolve(otherPath)"),
                ("HttpRequest.newBuilder(endpoint)", "HttpRequest.newBuilder(otherEndpoint)"),
                (".header(TOKEN_HEADER, token)", ".header(TOKEN_HEADER, cachedToken)"),
                (".header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)",
                 ".header(SERVICE_IDENTITY_HEADER, otherIdentity)"),
            ]
            for original, replacement in cases:
                with self.subTest(contract_id=entry["id"], original=original):
                    self.assert_source_mutation_rejected(relative, original, replacement)

    def test_client_transport_and_response_guards_require_executable_wiring(self) -> None:
        for entry in self.policy["httpClients"]:
            relative = entry["path"]
            cases = [
                ("import java.net.http.HttpClient;", "// import java.net.http.HttpClient;"),
                (".POST(HttpRequest.BodyPublishers.ofByteArray(body))", ".GET()"),
                (".followRedirects(HttpClient.Redirect.NEVER)",
                 '.followRedirects(HttpClient.Redirect.ALWAYS) /* .followRedirects(HttpClient.Redirect.NEVER) */'),
                ("OutboundHttpHeaders.propagateObservability(observability);",
                 "/* OutboundHttpHeaders.propagateObservability(observability); */"),
                (".timeout(requestTimeout)", "/* .timeout(requestTimeout) */"),
                ("BoundedHttpResponseReader.readBeforeDeadline(", "UnboundedResponseReader.read("),
            ]
            for original, replacement in cases:
                with self.subTest(contract_id=entry["id"], original=original):
                    self.assert_source_mutation_rejected(relative, original, replacement)

    def test_clients_cannot_add_borrowed_gateway_or_other_owner_credentials(self) -> None:
        for entry in self.policy["httpClients"]:
            forbidden = ["X-DWP-Product-Surface-Token", "X-DWP-Approval-Recovery-Token", "Authorization"]
            if entry["ownerToken"]["profile"] == "meeting-followup-authority":
                forbidden.append("X-DWP-Service-Token")
            for header in forbidden:
                with self.subTest(contract_id=entry["id"], header=header):
                    self.assert_source_mutation_rejected(
                        entry["path"], ".header(TOKEN_HEADER, token)",
                        f'.header(TOKEN_HEADER, token).header("{header}", token)',
                    )

    def test_notification_owner_must_validate_producer_bound_distinct_credentials(self) -> None:
        relative = self.entries[self.CONTRACT_IDS[0]]["ownerToken"]["securitySource"]
        cases = [
            ("request.getHeader(SOURCE_SERVICE_HEADER)", "request.getHeader(USER_HEADER)"),
            ("request.getHeader(SERVICE_TOKEN_HEADER)", "gatewayToken"),
            ("allowedProducers.contains(sourceService)", "allowedProducers.contains(otherSource)"),
            ("producerTokens.get(sourceService)", "producerTokens.get(gatewaySource)"),
            ("constantTimeEquals(expectedToken, presentedToken)", "true"),
            ("validateIdentityConfiguration();", "/* validateIdentityConfiguration(); */"),
            ("producerTokens.keySet().equals(allowedProducers)", "true"),
            ("distinctTokens.contains(gatewayToken)", "false"),
        ]
        for original, replacement in cases:
            with self.subTest(original=original):
                self.assert_source_mutation_rejected(relative, original, replacement)
        source = self.sources[relative]
        self.write(relative, "\n".join(line for line in source.splitlines()
                                      if "static final String" in line))
        self.assertTrue(CHECKER_MODULE.http_client_policy_violations(self.policy))

    def test_auth_owner_must_route_and_validate_exact_meeting_authority_credentials(self) -> None:
        relative = self.entries[self.CONTRACT_IDS[1]]["ownerToken"]["securitySource"]
        cases = [
            ("MEETING_SERVICE_IDENTITY.equals(identity)", "GATEWAY_SERVICE_IDENTITY.equals(identity)"),
            ("MEETING_FOLLOWUP_PATH)", "OTHER_PATH)"),
            ('"POST".equals(request.getMethod())', '"GET".equals(request.getMethod())'),
            ("MEETING_FOLLOWUP_PATH.equals(request.getRequestURI())", "true"),
            ("absentHeader(request, TOKEN_HEADER)", "true"),
            ("matches(expectedMeetingFollowupToken, meetingFollowupToken)", "true"),
            ("request, MEETING_FOLLOWUP_TOKEN_HEADER", "request, TOKEN_HEADER"),
            ("if (!gateway && !meeting)", "if (false)"),
            ("MessageDigest.isEqual(", "insecureEquals("),
            ("values.size() != 1", "false"),
        ]
        for original, replacement in cases:
            with self.subTest(original=original):
                self.assert_source_mutation_rejected(relative, original, replacement)
        expression = "matches(expectedMeetingFollowupToken, meetingFollowupToken)"
        for decoy in ("/* " + expression + " */", '"' + expression + '"'):
            with self.subTest(decoy=decoy):
                self.assert_source_mutation_rejected(relative, expression, "true /* disabled */ " + decoy)

    def test_owner_controller_mappings_cannot_be_replaced_with_comment_or_string_markers(self) -> None:
        for entry in self.policy["httpClients"]:
            relative = entry["ownerToken"]["endpointSource"]
            base, leaf = entry["ownerToken"]["path"].rsplit("/", 1)
            for annotation in (f'@RequestMapping("{base}")', f'@PostMapping("/{leaf}")'):
                for replacement in (annotation.replace(base, "/changed").replace(f'/{leaf}', "/changed"),
                                    "/* " + annotation + " */",
                                    'String note = """\n' + annotation + '\n""";'):
                    with self.subTest(contract_id=entry["id"], annotation=annotation,
                                      replacement=replacement):
                        self.assert_source_mutation_rejected(relative, annotation, replacement)

    def test_auth_owner_filter_must_be_installed_with_its_dedicated_token(self) -> None:
        relative = self.entries[self.CONTRACT_IDS[1]]["ownerToken"]["securitySource"]
        source = self.sources[relative]
        start = source.index(".addFilterBefore(")
        end_marker = "AnonymousAuthenticationFilter.class)"
        end = source.index(end_marker, start) + len(end_marker)
        installation = source[start:end]
        replacements = (
            "",
            "/* " + installation + " */",
            ';\n        String installationDecoy = """\n' + installation + '\n"""',
            installation.replace("meetingFollowupAuthorityToken", "productSurfaceToken"),
        )
        for replacement in replacements:
            with self.subTest(replacement=replacement):
                self.assert_source_mutation_rejected(relative, installation, replacement)
        for annotation in ("@Configuration", "@Bean"):
            for replacement in ("", "/* " + annotation + " */"):
                with self.subTest(annotation=annotation, replacement=replacement):
                    self.assert_source_mutation_rejected(relative, annotation, replacement)

    def test_auth_denial_must_set_unauthorized_status_and_return_before_filter_chain(self) -> None:
        relative = self.entries[self.CONTRACT_IDS[1]]["ownerToken"]["securitySource"]
        source = self.sources[relative]
        start = source.index("if (!gateway && !meeting) {")
        end = source.index("\n            filterChain.doFilter(request, response);", start)
        guard = source[start:end]
        status = "response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());"
        self.assertIn(status, guard)
        self.assertEqual(1, guard.count("return;"))
        for replacement in (guard.replace("return;", ""),
                            guard.replace("return;", "/* return; */"),
                            guard.replace(status, ""),
                            guard.replace(status, "/* " + status + " */")):
            with self.subTest(replacement=replacement):
                self.assert_source_mutation_rejected(relative, guard, replacement)

    def test_owner_credential_acceptance_cannot_add_a_permissive_disjunction(self) -> None:
        auth = self.entries[self.CONTRACT_IDS[1]]["ownerToken"]["securitySource"]
        notification = self.entries[self.CONTRACT_IDS[0]]["ownerToken"]["securitySource"]
        meeting_match = "matches(expectedMeetingFollowupToken, meetingFollowupToken);"
        gateway_match = "matches(expectedToken, productSurfaceToken);"
        producer_match = "constantTimeEquals(expectedToken, presentedToken);"
        cases = [
            (auth, meeting_match, meeting_match[:-1] + " || true;"),
            (auth, meeting_match, meeting_match[:-1] + " || gateway;"),
            (auth, gateway_match, gateway_match[:-1] + " || true;"),
            (auth, "actual.getBytes(StandardCharsets.UTF_8));",
             "actual.getBytes(StandardCharsets.UTF_8)) || true;"),
            (notification, producer_match, producer_match[:-1] + " || true;"),
        ]
        for relative, original, replacement in cases:
            with self.subTest(relative=relative, replacement=replacement):
                self.assert_source_mutation_rejected(relative, original, replacement)

    def test_notification_security_filter_requires_executable_component_registration(self) -> None:
        relative = self.entries[self.CONTRACT_IDS[0]]["ownerToken"]["securitySource"]
        for replacement in ("", "/* @Component */"):
            with self.subTest(replacement=replacement):
                self.assert_source_mutation_rejected(relative, "@Component", replacement)
        source = self.sources[relative]
        start = source.index("@Component")
        end = source.index("{", source.index("public class NotificationSecurityFilter", start)) + 1
        declaration = source[start:end]
        decoy = (declaration.replace("@Component\n", "")
                 + '\n    private static final String registrationDecoy = """\n@Component\n""";')
        self.assert_source_mutation_rejected(relative, declaration, decoy)
        self.assert_source_mutation_rejected(relative, "extends OncePerRequestFilter", "")

    def test_notification_controller_must_require_the_executable_internal_actor(self) -> None:
        relative = self.entries[self.CONTRACT_IDS[0]]["ownerToken"]["endpointSource"]
        actor = "NotificationRequestContext.requireInternalActor()"
        self.assert_source_mutation_rejected(relative, actor, "null")
        self.assert_source_mutation_rejected(relative, actor, "null /* " + actor + " */")
        source = self.sources[relative]
        start = source.index("MaterializationResult result = materializer.materialize(")
        end_marker = "request, correlationId);"
        end = source.index(end_marker, start) + len(end_marker)
        invocation = source[start:end]
        decoy = ('String actorDecoy = """\n' + actor + '\n""";\n        '
                 + invocation.replace(actor, "null"))
        self.assert_source_mutation_rejected(relative, invocation, decoy)

    def test_owner_source_paths_reject_traversal_wrong_owner_alias_and_symlink_escape(self) -> None:
        for entry in self.policy["httpClients"]:
            for key in ("securitySource", "endpointSource"):
                relative = entry["ownerToken"][key]
                owner = entry["targetServices"][0]
                other = (self.root / entry["path"]).resolve()
                link = self.root / owner / "src/main/java/Linked.java"
                if link.exists() or link.is_symlink():
                    link.unlink()
                link.symlink_to(other)
                alias = self.root / owner / "Alias.java"
                if alias.exists() or alias.is_symlink():
                    alias.unlink()
                alias.symlink_to(self.root / relative)
                values = ("../Outside.java", str(self.root / relative), entry["path"],
                          str(link.relative_to(self.root)), str(alias.relative_to(self.root)))
                for value in values:
                    with self.subTest(contract_id=entry["id"], key=key, value=value):
                        policy = copy.deepcopy(self.policy)
                        changed = next(candidate for candidate in policy["httpClients"]
                                       if candidate["id"] == entry["id"])
                        changed["ownerToken"][key] = value
                        self.assertTrue(CHECKER_MODULE.policy_manifest_violations(policy))


class WorkflowRuntimeProofBoundaryTest(unittest.TestCase):
    def entry(self) -> dict:
        return {
            "id": "approval-runtime", "interfaceType": "internal-http",
            "sourceService": "dwp-approval-server", "targetServices": ["dwp-auth-server"],
            "path": CHECKER_MODULE.WORKFLOW_RUNTIME_CLIENT,
            "retryMode": "none", "failureMode": "fail-closed",
            "requiredMarkers": sorted(CHECKER_MODULE.WORKFLOW_RUNTIME_REQUIRED_MARKERS),
            "forbiddenMarkers": sorted(CHECKER_MODULE.WORKFLOW_RUNTIME_FORBIDDEN_MARKERS),
        }

    def source(self) -> str:
        return (CHECKER.parents[1] / CHECKER_MODULE.WORKFLOW_RUNTIME_CLIENT).read_text(encoding="utf-8")

    def test_registers_only_the_exact_current_proof_client(self) -> None:
        self.assertEqual([], CHECKER_MODULE.workflow_runtime_manifest_violations(self.entry()))
        self.assertEqual([], CHECKER_MODULE.workflow_runtime_source_violations(self.entry(), self.source()))

    def test_other_services_paths_and_token_exemptions_cannot_borrow_this_purpose(self) -> None:
        for field, value in (("sourceService", "dwp-gateway"), ("targetServices", ["dwp-platform-server"]),
                             ("path", "dwp-approval-server/src/main/java/Other.java"),
                             ("retryMode", "idempotent-only"), ("failureMode", "fail-contained"),
                             ("signedWorkload", {}), ("ownerToken", {})):
            with self.subTest(field=field):
                entry = self.entry(); entry[field] = value
                self.assertTrue(CHECKER_MODULE.workflow_runtime_manifest_violations(entry))

    def test_each_transport_and_credential_requirement_is_mandatory(self) -> None:
        for field in ("requiredMarkers", "forbiddenMarkers"):
            for marker in self.entry()[field]:
                with self.subTest(field=field, marker=marker):
                    entry = self.entry(); entry[field].remove(marker)
                    self.assertTrue(CHECKER_MODULE.workflow_runtime_manifest_violations(entry))

    def test_comments_cannot_replace_actual_private_body_token_bounds_or_verification(self) -> None:
        source = self.source()
        for original in (
            '.header(TOKEN_HEADER,exchange.transportToken())',
            '.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body()))',
            'verifier.verify(response.body(),exchange)',
            'exchange.body().length>BODY_MAX',
            'exchange.transportToken().length()>TRANSPORT_MAX',
            'ENDPOINT_PATH.equals(PATH)', 'TOKEN_HEADER.equals(HEADER)',
        ):
            with self.subTest(original=original):
                self.assertIn(original, source)
                changed = source.replace(original, '/* ' + original + ' */ null')
                self.assertTrue(CHECKER_MODULE.workflow_runtime_source_violations(self.entry(), changed))


class PolicyImpactProofBoundaryTest(unittest.TestCase):
    def entry(self) -> dict:
        return {
            "id": "approval-policy-impact", "interfaceType": "internal-http",
            "sourceService": "dwp-approval-server", "targetServices": ["dwp-auth-server"],
            "path": CHECKER_MODULE.POLICY_IMPACT_CLIENT,
            "retryMode": "none", "failureMode": "fail-closed",
            "requiredMarkers": sorted(CHECKER_MODULE.POLICY_IMPACT_REQUIRED_MARKERS),
            "forbiddenMarkers": sorted(CHECKER_MODULE.POLICY_IMPACT_FORBIDDEN_MARKERS),
        }

    def source(self) -> str:
        return (CHECKER.parents[1] / CHECKER_MODULE.POLICY_IMPACT_CLIENT).read_text(encoding="utf-8")

    def test_registers_only_the_exact_policy_impact_proof_client(self) -> None:
        self.assertEqual([], CHECKER_MODULE.policy_impact_manifest_violations(self.entry()))
        self.assertEqual([], CHECKER_MODULE.policy_impact_source_violations(self.entry(), self.source()))

    def test_other_services_and_tokens_cannot_borrow_this_purpose(self) -> None:
        for field, value in (("sourceService", "dwp-gateway"), ("targetServices", ["dwp-platform-server"]),
                             ("path", CHECKER_MODULE.WORKFLOW_RUNTIME_CLIENT),
                             ("retryMode", "idempotent-only"), ("failureMode", "fail-contained"),
                             ("signedWorkload", {}), ("ownerToken", {})):
            with self.subTest(field=field):
                entry = self.entry(); entry[field] = value
                self.assertTrue(CHECKER_MODULE.policy_impact_manifest_violations(entry))

    def test_each_transport_and_credential_requirement_is_mandatory(self) -> None:
        for field in ("requiredMarkers", "forbiddenMarkers"):
            for marker in self.entry()[field]:
                with self.subTest(field=field, marker=marker):
                    entry = self.entry(); entry[field].remove(marker)
                    self.assertTrue(CHECKER_MODULE.policy_impact_manifest_violations(entry))

    def test_comments_cannot_replace_private_body_token_bounds_or_verification(self) -> None:
        source = self.source()
        for original in (
            '.header(TOKEN_HEADER, exchange.transport())',
            '.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body()))',
            'verifier.verify(response.body(), exchange)',
            'exchange.body().length > BODY_LIMIT',
            'exchange.transport().length() > TRANSPORT_LIMIT',
            'ENDPOINT_PATH.equals(PATH)', 'TOKEN_HEADER.equals(HEADER)',
        ):
            with self.subTest(original=original):
                self.assertIn(original, source)
                changed = source.replace(original, '/* ' + original + ' */ null')
                self.assertTrue(CHECKER_MODULE.policy_impact_source_violations(self.entry(), changed))


class InformationReplayProofBoundaryTest(unittest.TestCase):
    def entry(self) -> dict:
        return {
            "id": "approval-information-replay", "interfaceType": "internal-http",
            "sourceService": "dwp-approval-server", "targetServices": ["dwp-auth-server"],
            "path": CHECKER_MODULE.INFORMATION_REPLAY_CLIENT,
            "retryMode": "none", "failureMode": "fail-closed",
            "requiredMarkers": sorted(CHECKER_MODULE.INFORMATION_REPLAY_REQUIRED_MARKERS),
            "forbiddenMarkers": sorted(CHECKER_MODULE.INFORMATION_REPLAY_FORBIDDEN_MARKERS),
        }

    def source(self) -> str:
        return (CHECKER.parents[1] / CHECKER_MODULE.INFORMATION_REPLAY_CLIENT).read_text(encoding="utf-8")

    def test_registers_only_the_exact_information_replay_proof_client(self) -> None:
        self.assertEqual([], CHECKER_MODULE.information_replay_manifest_violations(self.entry()))
        self.assertEqual([], CHECKER_MODULE.information_replay_source_violations(self.entry(), self.source()))

    def test_other_services_and_tokens_cannot_borrow_this_purpose(self) -> None:
        for field, value in (("sourceService", "dwp-gateway"), ("targetServices", ["dwp-platform-server"]),
                             ("path", CHECKER_MODULE.WORKFLOW_RUNTIME_CLIENT),
                             ("retryMode", "idempotent-only"), ("failureMode", "fail-contained"),
                             ("signedWorkload", {}), ("ownerToken", {})):
            with self.subTest(field=field):
                entry = self.entry(); entry[field] = value
                self.assertTrue(CHECKER_MODULE.information_replay_manifest_violations(entry))

    def test_each_transport_and_credential_requirement_is_mandatory(self) -> None:
        for field in ("requiredMarkers", "forbiddenMarkers"):
            for marker in self.entry()[field]:
                with self.subTest(field=field, marker=marker):
                    entry = self.entry(); entry[field].remove(marker)
                    self.assertTrue(CHECKER_MODULE.information_replay_manifest_violations(entry))

    def test_comments_cannot_replace_private_body_token_bounds_or_verification(self) -> None:
        source = self.source()
        for original in (
            '.header(TOKEN_HEADER,exchange.token())',
            '.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body()))',
            'verifier.verify(response.body(),exchange)',
            'exchange.body().length>BODY_MAX',
            'exchange.token().length()>TOKEN_MAX',
            'ENDPOINT_PATH.equals(PATH)', 'TOKEN_HEADER.equals(HEADER)',
        ):
            with self.subTest(original=original):
                self.assertIn(original, source)
                changed = source.replace(original, '/* ' + original + ' */ null')
                self.assertTrue(CHECKER_MODULE.information_replay_source_violations(self.entry(), changed))


class WorkflowPlanningProofBoundaryTest(unittest.TestCase):
    def entry(self) -> dict:
        return {
            "id": "approval-workflow-admin-planning", "interfaceType": "internal-http",
            "sourceService": "dwp-approval-server", "targetServices": ["dwp-auth-server"],
            "path": CHECKER_MODULE.WORKFLOW_PLANNING_CLIENT,
            "retryMode": "none", "failureMode": "fail-closed",
            "requiredMarkers": sorted(CHECKER_MODULE.WORKFLOW_PLANNING_REQUIRED_MARKERS),
            "forbiddenMarkers": sorted(CHECKER_MODULE.WORKFLOW_PLANNING_FORBIDDEN_MARKERS),
        }

    def source(self) -> str:
        return (CHECKER.parents[1] / CHECKER_MODULE.WORKFLOW_PLANNING_CLIENT).read_text(encoding="utf-8")

    def test_registers_only_the_exact_admin_planning_proof_client(self) -> None:
        self.assertEqual([], CHECKER_MODULE.workflow_planning_manifest_violations(self.entry()))
        self.assertEqual([], CHECKER_MODULE.workflow_planning_source_violations(self.entry(), self.source()))

    def test_other_services_and_runtime_purposes_cannot_borrow_this_contract(self) -> None:
        for field, value in (("sourceService", "dwp-gateway"), ("targetServices", ["dwp-platform-server"]),
                             ("path", CHECKER_MODULE.WORKFLOW_RUNTIME_CLIENT),
                             ("retryMode", "idempotent-only"), ("failureMode", "fail-contained"),
                             ("signedWorkload", {}), ("ownerToken", {})):
            with self.subTest(field=field):
                entry = self.entry(); entry[field] = value
                self.assertTrue(CHECKER_MODULE.workflow_planning_manifest_violations(entry))

    def test_each_transport_bound_and_credential_requirement_is_mandatory(self) -> None:
        for field in ("requiredMarkers", "forbiddenMarkers"):
            for marker in self.entry()[field]:
                with self.subTest(field=field, marker=marker):
                    entry = self.entry(); entry[field].remove(marker)
                    self.assertTrue(CHECKER_MODULE.workflow_planning_manifest_violations(entry))

    def test_comments_cannot_replace_private_bytes_token_or_signature_verification(self) -> None:
        source = self.source()
        for original in (
            '.header(TOKEN_HEADER,exchange.token())',
            '.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body()))',
            'verifier.verify(response.body(),exchange)',
            'exchange.body().length>BODY_MAX',
            'exchange.token().length()>TOKEN_MAX',
            'ENDPOINT_PATH.equals(PATH)', 'TOKEN_HEADER.equals(HEADER)',
        ):
            with self.subTest(original=original):
                self.assertIn(original, source)
                changed = source.replace(original, '/* ' + original + ' */ null')
                self.assertTrue(CHECKER_MODULE.workflow_planning_source_violations(self.entry(), changed))


class CurrentSignatureAndSlaProofBoundaryTest(unittest.TestCase):
    def entry(self, name: str, profile: dict) -> dict:
        return {
            "id": name, "classification": name + "-current-source", "interfaceType": "internal-http",
            "sourceService": profile["sourceService"], "targetServices": [profile["targetService"]],
            "path": profile["path"], "retryMode": "none", "failureMode": "fail-closed",
            "requiredMarkers": sorted(CHECKER_MODULE.current_proof_required(profile)),
            "forbiddenMarkers": sorted(CHECKER_MODULE.current_proof_forbidden(profile)),
        }

    def sources(self, profile: dict) -> tuple[str, dict]:
        path = CHECKER.parents[1] / profile["path"]
        return path.read_text(encoding="utf-8"), {
            profile[kind]: path.with_name(profile[kind] + ".java").read_text(encoding="utf-8")
            for kind in ("protocol", "response", "verification") + (("issuer",) if "issuer" in profile else ())
        }

    def test_registers_only_actual_closed_client_and_independent_verified_response(self) -> None:
        self.assertEqual(3, len(CHECKER_MODULE.CURRENT_PROOF_CLIENTS))
        for name, profile in CHECKER_MODULE.CURRENT_PROOF_CLIENTS.items():
            with self.subTest(profile=name):
                entry = self.entry(name, profile); source, supports = self.sources(profile)
                self.assertEqual([], CHECKER_MODULE.current_proof_manifest_violations(entry))
                self.assertEqual([], CHECKER_MODULE.current_proof_source_violations(entry, source, supports))

    def test_wrong_service_target_path_profile_and_retry_cannot_borrow_a_purpose(self) -> None:
        for name, profile in CHECKER_MODULE.CURRENT_PROOF_CLIENTS.items():
            for field, value in (("sourceService", "dwp-gateway"), ("targetServices", ["dwp-platform-server"]),
                                 ("path", CHECKER_MODULE.WORKFLOW_RUNTIME_CLIENT), ("interfaceType", "external-connector"),
                                 ("retryMode", "idempotent-only"), ("failureMode", "fail-contained"),
                                 ("signedWorkload", {}), ("ownerToken", {})):
                with self.subTest(profile=name, field=field):
                    entry = self.entry(name, profile); entry[field] = value
                    self.assertTrue(CHECKER_MODULE.current_proof_manifest_violations(entry))

    def test_each_transport_and_borrowed_credential_requirement_is_mandatory(self) -> None:
        for name, profile in CHECKER_MODULE.CURRENT_PROOF_CLIENTS.items():
            for field in ("requiredMarkers", "forbiddenMarkers"):
                for marker in self.entry(name, profile)[field]:
                    with self.subTest(profile=name, field=field, marker=marker):
                        entry = self.entry(name, profile); entry[field].remove(marker)
                        self.assertTrue(CHECKER_MODULE.current_proof_manifest_violations(entry))

    def test_comments_and_string_notes_cannot_replace_executable_client_guards(self) -> None:
        common = [r"ENDPOINT_PATH\.equals\([^)]*PATH\)", r"TOKEN_HEADER\.equals\([^)]*HEADER\)",
                  r"\.POST\(HttpRequest\.BodyPublishers\.ofByteArray\(exchange\.body\(\)\)\)",
                  r"\.connectTimeout\(Duration\.ofSeconds\(3\)\)", r"\.timeout\(Duration\.ofSeconds\(5\)\)",
                  r"pending\.get\(5,\s*(?:java\.util\.concurrent\.)?TimeUnit\.SECONDS\)",
                  r"\.followRedirects\(HttpClient\.Redirect\.NEVER\)",
                  r"pending\.cancel\(true\)", r"body\.cancel\(\)",
                  r'String\s+ENDPOINT_PATH\s*=\s*"[^"]+";',
                  r'String\s+TOKEN_HEADER\s*=\s*"[^"]+";']
        for name, profile in CHECKER_MODULE.CURRENT_PROOF_CLIENTS.items():
            source, supports = self.sources(profile)
            if name == "approval-system-sla":
                specific = [r"\.header\(TOKEN_HEADER,\s*exchange\.transport\(\)\)",
                            r"import\s+java\.net\.http\.\*;",
                            r"verifier\.verify\(response\.body\(\),\s*exchange\)"]
            else:
                specific = [r"\.header\(TOKEN_HEADER,\s*exchange\.token\(\)\)", r"import\s+java\.net\.http\.HttpClient;",
                            r"jwt\.verify\(new RSASSAVerifier\(rsa\)\)" if name == "approval-signature" else
                            r"verifier\.verify\(response\.body\(\),\s*plan,\s*exchange\.nonce\(\),\s*exchange\.bodySha256\(\),\s*exchange\.expiresAt\(\)\)"]
            for pattern in common + specific:
                matches = CHECKER_MODULE.executable_literal_matches(pattern, source)
                self.assertTrue(matches, (name, pattern))
                match = matches[0]
                for replacement in ("/* " + match.group() + " */", "String note = " + json.dumps(match.group()) + ";"):
                    with self.subTest(profile=name, pattern=pattern, replacement=replacement[:12]):
                        mutated = source[:match.start()] + replacement + source[match.end():]
                        self.assertTrue(CHECKER_MODULE.current_proof_source_violations(self.entry(name, profile), mutated, supports))

    def test_protocol_immutable_bytes_purpose_bounds_and_private_verification_cannot_be_comments(self) -> None:
        for name, profile in CHECKER_MODULE.CURRENT_PROOF_CLIENTS.items():
            source, supports = self.sources(profile)
            if name == "approval-system-sla":
                continue  # Its separate issuer and typed expiry guards are exercised below.
            originals = {
                "protocol": ["body = body.clone()", "return body.clone()", profile["purpose"], profile["header"], profile["endpoint"]],
                "response": ["subscription.cancel()", "32768" if name == "approval-signature" else "524288"],
                "verification": ["private Verified(", "expires.isAfter(now)" if name == "approval-signature" else "expires > requestExpiresAt.getEpochSecond()"],
            }
            for kind, values in originals.items():
                key = profile[kind]
                for original in values:
                    self.assertIn(original, supports[key])
                    with self.subTest(profile=name, kind=kind, original=original):
                        changed = dict(supports); changed[key] = supports[key].replace(original, "/* " + original + " */ null")
                        self.assertTrue(CHECKER_MODULE.current_proof_source_violations(self.entry(name, profile), source, changed))

    def test_borrowed_role_headers_purpose_tokens_and_qualified_retry_fail_closed(self) -> None:
        for name, profile in CHECKER_MODULE.CURRENT_PROOF_CLIENTS.items():
            source, supports = self.sources(profile)
            for marker in CHECKER_MODULE.current_proof_forbidden(profile):
                with self.subTest(profile=name, marker=marker):
                    self.assertTrue(CHECKER_MODULE.current_proof_source_violations(
                        self.entry(name, profile), source + '\nString borrowed = "' + marker + '";', supports))
            self.assertTrue(CHECKER_MODULE.current_proof_source_violations(self.entry(name, profile),
                            source + "\n@io.github.resilience4j.retry.annotation.Retry(name=\"borrowed\")", supports))

    def test_old_contracts_forbid_all_three_headers_without_permission_or_profile_changes(self) -> None:
        import json
        policy = json.loads((CHECKER.parents[1] / "docs/architecture/service-interface-contracts.json").read_text())
        headers = {profile["header"] for profile in CHECKER_MODULE.CURRENT_PROOF_CLIENTS.values()}
        for entry in policy["httpClients"]:
            with self.subTest(client=entry["id"]):
                self.assertTrue(headers - set(entry["requiredMarkers"]) <= set(entry["forbiddenMarkers"]))

    def test_registered_client_cannot_disappear_by_removing_its_http_import(self) -> None:
        for name, profile in CHECKER_MODULE.CURRENT_PROOF_CLIENTS.items():
            source, supports = self.sources(profile)
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory); path = root / profile["path"]; path.parent.mkdir(parents=True)
                imported = "import java.net.http.*;" if name == "approval-system-sla" else "import java.net.http.HttpClient;"
                path.write_text(source.replace(imported, ""))
                for key, raw in supports.items(): path.with_name(key + ".java").write_text(raw)
                previous = CHECKER_MODULE.ROOT
                try:
                    CHECKER_MODULE.ROOT = root
                    self.assertTrue(CHECKER_MODULE.http_client_policy_violations({"httpClients": [self.entry(name, profile)]}))
                finally: CHECKER_MODULE.ROOT = previous

    def test_system_sla_separate_issuer_signature_echoes_expiry_and_bounds_cannot_be_comments(self) -> None:
        name = "approval-system-sla"; profile = CHECKER_MODULE.CURRENT_PROOF_CLIENTS[name]
        source, supports = self.sources(profile)
        originals = {
            "protocol": [profile["endpoint"], profile["header"], "524288", profile["purpose"],
                         "APPROVAL_SYSTEM_SLA_SOURCE_V1", "APPROVAL_SYSTEM_SLA_ATTESTATION_V1",
                         "dwp-approval-system-sla-owner", "dwp-auth-system-sla-owner", "dwp-approval-system-sla-transport",
                         "dwp-auth-system-sla-transport", "dwp-auth-system-sla-attestation", "dwp-approval-system-sla-attestation"],
            "issuer": ["private Exchange(", "this.body = body.clone()", "return body.clone()",
                       "standard(OWNER_ISSUER, OWNER_AUDIENCE, OWNER_PURPOSE,",
                       "standard(TRANSPORT_ISSUER, TRANSPORT_AUDIENCE, TRANSPORT_PURPOSE,",
                       "sign(owner, keys.owner(), json)", "sign(transport, keys.transport(), json)",
                       "token.sign(new RSASSASigner(key.toRSAPrivateKey()))",
                       'json.bytes(Map.of("sourceProof", proof, "bindings", bindings))',
                       'transport.put("sourceProofJti", ownerId)', 'transport.put("bodySha256", sha(body))',
                       'transport.put("bindingsSha256", bindingHash)', 'transport.put("method", "POST")',
                       'transport.put("path", PATH)', "exp <= now || exp > now + 30",
                       "body.length > BODY_LIMIT", "token.length() > 2048",
                       "new Exchange(seal, body, token, ownerId, transportId, bindingHash, Instant.ofEpochSecond(now), Instant.ofEpochSecond(exp))"],
            "response": ["subscription.cancel()", "buffer.remaining() > SystemSlaSourceProtocol.BODY_LIMIT - bytes.size()"],
            "verification": ["private Verified(", 'jwt.verify(new RSASSAVerifier(keys.attestation(text(header, "kid", 80))))',
                             'SystemSlaJson.keys(response, Set.of("sourceAttestation"))', "SystemSlaJson.keys(claims, ATTESTATION_FIELDS)",
                             'ATTESTATION_PURPOSE.equals(text(claims, "purpose", 100))',
                             'exchange.ownerId().equals(uuid(claims, "sourceProofJti").toString())',
                             'exchange.transportId().equals(uuid(claims, "transportProofJti").toString())',
                             'sha(exchange.body()).equals(hash(claims, "bodySha256"))',
                             'exchange.bindingHash().equals(hash(claims, "bindingsSha256"))',
                             'hash(exchange.seal().bindings(), "sourceDigest").equals(hash(claims, "sourceDigest"))',
                             "expiry <= now || expiry <= issued || expiry - issued > 30 || expiry > exchange.expiresAt().getEpochSecond()",
                             "evaluated.isBefore(exchange.ownerIssuedAt())", "evaluated.isAfter(clock.instant())",
                             "!evaluated.isBefore(expires)", 'revision.equals("asla-" + vector)',
                             "this.recipients = recipients.deepCopy()",
                             "actual.size() != expected.size() || actual.isEmpty() || actual.size() > 1000"],
        }
        for kind, values in originals.items():
            key = profile[kind]
            for original in values:
                self.assertIn(original, supports[key])
                for replacement in ("/* " + original + " */ null", "String note = " + json.dumps(original) + ";"):
                    with self.subTest(kind=kind, original=original, replacement=replacement[:12]):
                        changed = dict(supports); changed[key] = supports[key].replace(original, replacement)
                        self.assertTrue(CHECKER_MODULE.current_proof_source_violations(self.entry(name, profile), source, changed))

    def test_system_sla_real_request_guards_are_before_transport_and_chunks_before_copy(self) -> None:
        name = "approval-system-sla"; profile = CHECKER_MODULE.CURRENT_PROOF_CLIENTS[name]
        source, supports = self.sources(profile); entry = self.entry(name, profile)
        for original in ("endpoint.getRawQuery() != null", "endpoint.getRawFragment() != null",
                         "exchange.body().length > SystemSlaSourceProtocol.BODY_LIMIT", "exchange.transport().length() > 2048"):
            with self.subTest(guard=original):
                changed = source.replace(original, "/* " + original + " */ false")
                self.assertTrue(CHECKER_MODULE.current_proof_source_violations(entry, changed, supports))
        request = "exchange.body().length > SystemSlaSourceProtocol.BODY_LIMIT"
        changed = source.replace(request, "false") + "\nboolean tooLate = " + request + ";"
        self.assertTrue(any("before building" in error for error in CHECKER_MODULE.current_proof_source_violations(entry, changed, supports)))
        key = profile["response"]; bound = "buffer.remaining() > SystemSlaSourceProtocol.BODY_LIMIT - bytes.size()"
        changed = dict(supports); changed[key] = supports[key].replace(bound, "false") + "\nboolean tooLate = " + bound + ";"
        self.assertTrue(any("before allocating" in error for error in CHECKER_MODULE.current_proof_source_violations(entry, source, changed)))

    def test_system_sla_attestation_field_set_is_exact_not_a_borrowed_profile(self) -> None:
        name = "approval-system-sla"; profile = CHECKER_MODULE.CURRENT_PROOF_CLIENTS[name]
        source, supports = self.sources(profile); key = profile["protocol"]
        for original, replacement in (('"sourceDigest", "authority", "recipients"', '"sourceDigest", "authority"'),
                                      ('"recipients"', '"recipients", "population"'),
                                      ('"sourceProofJti"', '"sourceProofJti", "sourceProofJti"'),
                                      ('"transportProofJti"', '"requestNonce"')):
            with self.subTest(replacement=replacement):
                changed = dict(supports); changed[key] = supports[key].replace(original, replacement)
                self.assertTrue(CHECKER_MODULE.current_proof_source_violations(self.entry(name, profile), source, changed))


class HomeProviderTransportBoundaryTest(unittest.TestCase):

    def setUp(self) -> None:
        policy = json.loads(
            (CHECKER.parents[1] / "docs/architecture/service-interface-contracts.json")
            .read_text(encoding="utf-8")
        )
        self.policy = policy

    def test_factory_and_transport_contracts_are_exact_and_cannot_be_borrowed(self) -> None:
        self.assertFalse(any(
            "purpose-specific service token" in problem
            for problem in CHECKER_MODULE.policy_manifest_violations(self.policy)
        ))
        for profile in (CHECKER_MODULE.HOME_PROVIDER_FACTORY, CHECKER_MODULE.HOME_PROVIDER_CLIENT):
            index = next(index for index, entry in enumerate(self.policy["httpClients"])
                         if entry["id"] == profile["id"])
            mutations = {
                "id": "borrowed-home-provider",
                "classification": "borrowed-transport",
                "sourceService": "dwp-approval-server",
                "path": "dwp-platform-server/src/main/java/Borrowed.java",
                "targetServices": ["dwp-approval-server"],
                "retryMode": "idempotent-only",
                "failureMode": "fail-closed",
            }
            for field, value in mutations.items():
                with self.subTest(profile=profile["id"], field=field):
                    changed = copy.deepcopy(self.policy)
                    changed["httpClients"][index][field] = value
                    self.assertTrue(CHECKER_MODULE.policy_manifest_violations(changed))

            for field in ("requiredMarkers", "forbiddenMarkers"):
                for marker in self.policy["httpClients"][index][field]:
                    with self.subTest(profile=profile["id"], field=field, marker=marker):
                        changed = copy.deepcopy(self.policy)
                        changed["httpClients"][index][field].remove(marker)
                        self.assertTrue(CHECKER_MODULE.policy_manifest_violations(changed))

    def test_factory_preserves_boot_builder_and_transport_enforces_bounds(self) -> None:
        for profile in (CHECKER_MODULE.HOME_PROVIDER_FACTORY, CHECKER_MODULE.HOME_PROVIDER_CLIENT):
            entry = next(entry for entry in self.policy["httpClients"]
                         if entry["id"] == profile["id"])
            source = (CHECKER.parents[1] / profile["path"]).read_text(encoding="utf-8")
            self.assertEqual([], CHECKER_MODULE.home_provider_source_violations(entry, source))
            self.assertNotIn("RestClient.builder()", source)

        transport = next(entry for entry in self.policy["httpClients"]
                         if entry["id"] == CHECKER_MODULE.HOME_PROVIDER_CLIENT["id"])
        source = (CHECKER.parents[1] / transport["path"]).read_text(encoding="utf-8")
        mutations = (
            (".followRedirects(HttpClient.Redirect.NEVER)",
             ".followRedirects(HttpClient.Redirect.NORMAL)"),
            ("deadline.isAfter(now.plus(MAX_DEADLINE_AHEAD))", "false"),
            ("response.tenantId() != context.tenantId()", "false"),
            ("response.userId() != context.userId()", "false"),
        )
        for old, new in mutations:
            with self.subTest(marker=old):
                self.assertTrue(CHECKER_MODULE.home_provider_source_violations(
                    transport, source.replace(old, new)
                ))


if __name__ == "__main__":
    unittest.main()
