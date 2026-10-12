# HRIS G7 Product Authorization v36 correction

Date: 2026-10-12

## Decision

Product Authorization v35 remains byte-for-byte immutable at checksum
`38ef33070a33b5da9a7825c764bd24daacd24c8ca4008f3a926727321d3d098d`.
The corrective v36 release appends one dedicated capability,
`hcm.operations.performance.freeze`, and upgrades only
`route.hcm.operations.performance-population-freeze.action` to that capability.

The v36 checksum is
`12e4b9329ab53ab1efd6c5f6e23ccc7869c4184a79e5a64f614fe9c40a9e704b`.
It contains 237 capabilities and the same 974 routes as v35. The route is now
an exact high-risk command with activation policy `STEPUP-MGMT-HIGH-V1`, target
type `PERFORMANCE_CYCLE`, target path parameter `cycleId`, owner service
`people`, audience `dwp-people-server`, and expected object version header
`X-DWP-Expected-Object-Version`.

The medium-risk `hcm.operations.performance.update` capability remains the
authority for population preview. This avoids requiring step-up for a
side-effect-free preview while preventing the irreversible freeze from being
advertised or executed under a medium-risk descriptor.

## Runtime binding

- Auth seed lineage and the seed index now accept versions 1 through 36.
- Flyway V244 declares the v36 DRAFT seed without importing, approving,
  activating, assigning, updating, or deleting any release.
- People, Time, and Platform PEP projections are emitted as v36 artifacts.
- People and Platform runtime registries load the v36 projections and pin the
  v36 registry checksum. The People closure requires the new capability and
  exposes the exact freeze step-up binding.
- The v35 contract and v35 owner projections remain generated and verified as
  legacy immutable artifacts.

## Focused verification

- Product-authorization generator and fixture generator checks pass.
- Approved-snapshot OpenAPI export check passes with 12 services and 1664
  public Gateway paths.
- The focused Python contract batch passes 47 tests, including preservation of
  v35 and the exact v36 delta.
- Auth lineage/loader/migration/fixture tests pass.
- People v36 PEP registry tests pass, including the dedicated freeze capability
  and exact step-up target.
- Platform v36 PEP registry/filter tests pass.

No repository-wide suite or W1 acceptance run was used for this correction.
