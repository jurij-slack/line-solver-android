# Authorized scope

2026-10-25: separate the existing Kotlin Android application into this standalone
repository. Preserve application behavior and compatibility; make materials,
catalogue generation and regression tests independent of the Python checkout.
Acceptance: byte-identical data snapshots, native generator reproduction, debug
build, complete JVM suite, lint, and a tracked-files-only build with Python hidden.

No new mechanics, feature reactivation, Dynamics or Python integration phase is
approved. Detailed migration status and evidence: MIGRATION_REPORT.md.

- [x] Project-local material and fixture inputs; checksums preserved.
- [x] Native generator/tests, debug/instrumentation builds and lint pass.
- [x] Tracked-files-only checkout passes with Python project hidden.
- [x] Final sibling-root acceptance; personal validation preserved.
- [x] Publication ignores, staged-file review, reports and handoff complete.

Migration complete. Stop; no next feature phase is authorized.
