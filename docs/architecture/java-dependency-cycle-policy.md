# Java dependency-cycle ratchet

Backend production Java must not add or enlarge a source-level strongly connected component
(SCC). Spring constructor dependency cycles are prohibited without exception because they make
application startup order-dependent and fail under the default circular-reference policy.

`scripts/check-java-dependency-cycles.py` resolves explicit production imports and same-package
class references after removing comments and literals. It compares SCC membership exactly with
`java-dependency-cycle-baseline.json`; a new member, a new SCC, a removed SCC with a stale entry,
duplicate metadata, or an expired `reviewBy` date fails the build. The checker separately builds a
Spring component constructor graph. Runtime cycles are never accepted by the baseline.

The current 10 entries are compile-time owner/helper couplings, not Spring bean cycles. They use
manually constructed helpers, owner-nested immutable records, inheritance-based repository splits,
or the intentional Jackson DTO/deserializer contract. Each exception records
one accountable owner, a reason, and a mandatory review date. Resolving a cycle requires removing
its entry in the same change; moving classes or adding a member cannot silently expand it.

Run the contract locally with:

```shell
./gradlew checkJavaDependencyCycles
```

The root `check` lifecycle depends on this task, including the fail-closed checker regressions.
