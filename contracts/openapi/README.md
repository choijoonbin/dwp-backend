# OpenAPI contract policy

Every HTTP service publishes an OpenAPI 3 contract from its Spring MVC or WebFlux controller model. Contract export is enabled only in local and CI environments with `DWP_OPENAPI_ENABLED=true`; production ingress does not expose service documentation.

The contract lifecycle is:

1. A backend change updates controller validation and the generated OpenAPI document in the same pull request.
2. CI compares normalized contracts and rejects undocumented or breaking changes unless a versioned migration is declared.
3. Frontend clients and runtime validators are generated from the approved Gateway-facing contract. Handwritten duplicate DTOs are transitional and cannot be introduced for new endpoints.
4. Internal service APIs are additionally governed by `docs/architecture/service-interface-contracts.json`. They are never exposed to browser clients.

`scripts/export-openapi-contracts.py --write` captures normalized service documents and composes `gateway-public.json`. The composed contract applies the actual Gateway prefixes, excludes `/internal/**`, namespaces component names, and is the source contract for browser clients. CI starts the real services and runs the same command with `--check`; controller drift therefore cannot merge without an explicit contract update.

OpenAPI endpoints are absent by default. `scripts/devctl.py` enables them only for the local/CI process environment, while production readiness checks reject an enabled endpoint in a production environment.
