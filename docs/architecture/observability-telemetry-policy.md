# Observability telemetry policy

DWP separates operational telemetry from compliance evidence.

- OpenTelemetry traces, metrics, and correlated logs diagnose latency, saturation, errors, and dependency health.
- API history and audit records remain governed evidence stores with their own integrity, privacy, access, and retention controls.
- Services export OTLP to an environment-owned Collector. Applications do not bind directly to a monitoring vendor.
- `OTEL_SDK_DISABLED=true` is the local default. Production deployment must set it to `false`, configure `OTEL_EXPORTER_OTLP_ENDPOINT`, and declare an explicit sampler.
- Production startup is fail-closed when OTLP still points at the local default, audit or API-history export is disabled, privacy hashing is unkeyed, or the Gateway retains development CORS origins.
- Trace and metric attributes must use bounded cardinality. User IDs, email addresses, raw URLs, request bodies, access tokens, and tenant secrets are forbidden telemetry attributes.
- Collector pipelines must apply resource detection, attribute filtering, memory limits, batching, TLS, and authenticated export before production approval.

The existing W3C `traceparent` and `tracestate` propagation remains the wire contract. OpenTelemetry is the implementation standard for spans and metrics; the DWP correlation ID remains a separate support-facing request identifier.
