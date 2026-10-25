# Progress — standalone migration complete

2026-10-25. Read MIGRATION_REPORT.md and validation/MIGRATION_RESULTS.md.
Final sibling-root and tracked-files-only/Python-hidden builds pass: debug and
instrumentation APKs; 204 JVM passes, one expected replay skip; lint zero errors,
one existing qualifier warning. Kotlin catalogue generation reproduces exactly.
Three source JSON snapshots and packaged material/catalogue assets match byte for
byte. Runtime Kotlin, manifest, saved-rig/material identities and fixtures unchanged.

All 1,618 original private validation files remain at their original Python-project
location. Python source/tests/data unchanged; 30 material regressions pass. Optional
Python oracle/workbook tools moved to that project's scripts/android_reference.
The Android tree is staged on main; no commit/remote/publication. No device changes.

Next: stop. Review/commit/publish only when separately instructed. Preserve original
signing identity privately; no new mechanics, reactivation or Dynamics authorized.
