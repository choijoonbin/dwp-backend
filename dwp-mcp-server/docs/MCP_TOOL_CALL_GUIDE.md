# MCP Tool Call Guide

## Base URL
- Gateway: `http://localhost:8080/api/mcp/tools`
- Direct: `http://localhost:8090/mcp/tools` (if mapped that way in local env)

## Required Headers (all tools)
- `X-Tenant-ID`: tenant id
- `X-User-ID`: caller user id
- `X-Trace-ID`: optional, auto-generated if missing

If `X-User-ID` is missing, MCP returns:
- `success=false`
- `errorCode=INPUT_PARTIAL`
- `errorMessage="X-User-ID 헤더가 필요합니다."`

## Tool Endpoints
1. `POST /policy-regulation`
2. `POST /business-calendar`
3. `POST /master-data`
4. `POST /case-context`
5. `POST /evidence-verification`
6. `POST /rag-conflict-diagnostics`

## Normal Call Example
```bash
curl -X POST "http://localhost:8080/api/mcp/tools/master-data" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: 1" \
  -H "X-User-ID: 1" \
  -H "X-Trace-ID: test-trace-001" \
  -d '{"mccCode":"5814","expenseType":"SA","hrStatus":"WORKING"}'
```

## Log Keys for Verification
- Request summary log:
  - `tool`, `traceId`, `tenantId`, `userId`, `caseId`, `runId`
- Response summary log:
  - `tool`, `traceId`, `tenantId`, `userId`, `caseId`, `runId`, `success`, `decisionCode`, `latency_ms`

