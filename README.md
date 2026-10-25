# Line Solver for Android

A native Kotlin/Jetpack Compose application for static slackline rig calculations,
material inspection and saved rigs. The solver, material laws, catalogue importer
and tests run on Kotlin/JVM. Python is not required at runtime, build time or test
time. Simple and Mixed workflows retain their existing behavior. Bungee editing
remains temporarily unavailable; this repository migration does not enable it.

The independent Python reference solver develops and validates material models
and produces selected numerical reference fixtures. Android consumes versioned
snapshots; neither project needs to be installed beside the other.

## Build

Install JDK 25 (the Gradle daemon toolchain), Android SDK platform 37.0 / API 37,
and the build tools requested by Android Gradle Plugin 9.3.2. Set `JAVA_HOME` and
`ANDROID_HOME` for your installation, or put `sdk.dir=...` in ignored
`local.properties`. Accept the SDK licences. The Gradle wrapper downloads Gradle
9.5.0 and declared Maven dependencies on the first build. No Python setup is needed.

Run from this repository root:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew :app:generateWebbingCatalogue :app:verifyWebbingCatalogue
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. JVM test reports:
`app/build/reports/tests/testDebugUnitTest/`. Lint reports:
`app/build/reports/lint-results-debug.html`. The project defaults to one Gradle
worker, a 1 GiB daemon and 384 MiB JVM test heap to fit the existing environment.
Optional diagnostic report replay accepts `-PdebugReport=/path/to/report.json`;
without it, that single test is intentionally skipped. It is not a required fixture.
Instrumentation sources are retained; `assembleDebugAndroidTest` builds them.
Device testing requires separately preserving the installed application's private
files/preferences. Do not uninstall or clear a user's app to run tests.

## Data and regression fixtures

- `data/materials/webbing_material_models_v1.json`: byte-identical material snapshot
  copied from the Python project; the sole legacy numerical database in this app.
- `data/materials/webbings.json`: original offline catalogue input, with attribution.
- `data/materials/phase6_connector_research/connector_specs_research.json`:
  connector research regression fixture.
- `catalogue/`: reviewed reconciliation rules and deterministic generated outputs.
  Legacy curves are referenced, not independently editable duplicate definitions.
- `reference/` and `app/src/test/resources/`: checked-in numerical, material and
  saved-format baselines. JVM tests read them directly; they do not regenerate them.
- `specifications/`: portable format and mechanics documentation.
- `deferred/`: preserved feature snapshots needed by existing regression tests.
- `doc_archive/`: unmodified historical context, not the active implementation plan.

See [snapshot procedure](data/README.md), [catalogue documentation](catalogue/README.md),
and [migration evidence](MIGRATION_REPORT.md). Historical Python source hashes in
`reference/manifest.json` describe fixture provenance, not required local files.
The material snapshot has since changed; its current identity is in `data/SNAPSHOT.json`.
Python-only oracle/workbook tools remain in the Python project's
`scripts/android_reference/`; they are optional reference-development tools.

## Identity and signing

The application ID and namespace remain `org.linesolver.base`; versionCode `1`
and versionName `0.1.0` are unchanged. Rig formats, material identities, private
storage names and preferences are unchanged. An APK signed with the original
signing key and the same application ID can update an existing installation,
subject to Android's normal version requirements. Preserve that key privately,
increment the version code for a future release, and supply signing credentials
outside Git. No signing key or release signing configuration is added here.
A newly generated debug key does not replace the original signing identity.

Upstream data licence and ISA notices are bundled in `app/src/main/assets/licenses/`
and described in `branding/DATA_ATTRIBUTION.md`. These notices do not license the
entire application. No new application licence is selected by this migration.
No simulation is a field safety certification.
