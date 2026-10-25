# Explicit material and fixture snapshots

The independent Python repository is authoritative for material model development
and validation. This repository owns a versioned distribution snapshot and never
reads its neighbor during Gradle builds, tests or catalogue generation.

`SNAPSHOT.json` records source paths, date and SHA-256 of every imported input.
The source checkout had no usable Git metadata, so no source commit is claimed.
These content hashes are the exact source version for this migration.

To update deliberately:

1. Validate proposed models in the Python project. Record its commit/version when
   available. Review compatibility of immutable material IDs/revisions and saved
   content identities; do not silently substitute a changed fit for saved rigs.
2. Copy the approved JSON bytes into the same project-local path. Use explicit
   source/destination paths of your choosing; no neighboring checkout is assumed.
   Compare `sha256sum` on both files before proceeding.
3. Record source commit/version, date, source path, checksum and reason in
   `SNAPSHOT.json` and the change description. Retain source metadata and units.
   Update raw webbing/connector evidence only when intentionally approved.
4. From the Android root run `./gradlew :app:generateWebbingCatalogue`, review the
   catalogue/inventory/reconciliation diff, then run `./gradlew
   :app:verifyWebbingCatalogue testDebugUnitTest assembleDebug lintDebug`.
   Review attribution hashes if the covered catalogue files change.
5. Include reviewed inputs, generated outputs and snapshot metadata in the same
   versioned update. Do not edit numerical arrays independently inside the app.

`syncMaterialDatabase` packages the snapshot and generated webbing catalogue as
assets and JVM test resources. Raw webbing/connector inputs are development/test
evidence and are not additional runtime databases. Fixture regeneration through
Python is a separate reference-validation activity, never a build prerequisite.
