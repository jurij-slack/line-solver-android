# Migration validation — 2026-10-25

Fresh original-project baseline used JDK 25, --offline --max-workers=1,
-Dorg.gradle.jvmargs='-Xmx1g -Dfile.encoding=UTF-8',
-Pkotlin.compiler.execution.strategy=in-process, and the existing
validation/bungee_phase04/memory-limits.gradle (384 MiB test heap).

- :app:testDebugUnitTest --rerun-tasks: successful; 205 total, 204 passes,
  one expected ReplayReportTest skip, zero failures/errors.
- :app:verifyWebbingCatalogue: all artifacts reproduce exactly.

A clean tree was exported from the new Git index using git checkout-index --all.
No local.properties, output directories, personal validation data or project caches
were exported. Bubblewrap hid the original Projects directory using --tmpfs;
`test ! -e` confirmed the Python repository was absent before executing Gradle.
The checkout ran at an unrelated temporary path. JDK/SDK, existing Gradle caches
and the local debug signing directory were supplied as machine dependencies.

Executed from that isolated Android root, using JAVA_HOME and ANDROID_HOME:

```sh
./gradlew --offline --no-daemon assembleDebug testDebugUnitTest lintDebug \
  :app:generateWebbingCatalogue :app:verifyWebbingCatalogue assembleDebugAndroidTest
```

- Build successful in 3m 1s (final corrected isolation run).
- Debug APK and instrumentation APK built.
- 205 JVM tests: 204 passes, one expected replay skip, zero failures/errors.
- Lint: zero errors; one existing ObsoleteSdkInt warning for the intentional
  v26 adaptive-icon qualifier. The two earlier dependency-version notices are
  absent in this offline run.
- Kotlin catalogue generator runs without Python; catalogue, inventory and
  reconciliation reproduce exactly. Output bytes match the original snapshots.
- APK assets/webbing_material_models_v1.json equals source bytes, SHA-256
  57de2e9012dde5522e1080e3de239da1ade70ab2c14f98a3d2c185324f3b5edf.
- APK assets/webbing-catalogue-v1.json equals source bytes, SHA-256
  6dc2ddbe11ed1ac3771b4fed1dcb8ab4c7a6772d8f2396d49e8dee2295a57c6a.
- All imported inputs match the original Python source bytes; snapshot hashes are
  in data/SNAPSHOT.json. All runtime Kotlin/manifest, numerical reference JSON,
  catalogue outputs and saved-format fixtures remain unchanged.

Python-root checks (independent of Android):

```sh
.venv/bin/python -m pytest -q tests/test_material_update.py tests/test_expanded_materials.py
```

30 tests pass. Source/tests/production-data SHA-256 identities were captured before
migration and checked after it. Optional relocated Python tools compile and accept
--help/--android-root; numerical oracle regeneration was intentionally not run.
All 1,618 original validation files remain at their original paths and are checked
against their pre-migration hashes at finalization.

Security review covers every staged path and text file for private keys and common
credential signatures. No machine configuration, signing key, device backup or
personal export is staged. Existing licence/archive whitespace is retained;
initial git diff --check reports those pre-existing lines only.

Final sibling-root validation is recorded below after installation. No physical
phone interaction, remote publication or clean online dependency bootstrap is
performed as part of this migration.

## Final sibling-root acceptance

The identical Gradle task set above also passes from the final
line-solver-android root, with JAVA_HOME and ANDROID_HOME pointing to this
machine's existing JDK/SDK. Fresh output: 205 JVM tests, 204 passes, one expected
skip, zero failures/errors. Lint: zero errors, 1 existing warning.
Catalogue generation and verification reproduce all three artifacts exactly;
debug and instrumentation APKs are built. All 1,618 original validation files
remain byte-identical. Original migrated source directories were removed only
after this run passed; private evidence and original doc_archive remain in place.
The new Git index contains publication files only, on main, without a commit or
remote. No unresolved build/test blocker remains.
