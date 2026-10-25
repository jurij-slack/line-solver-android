# Standalone Android repository migration — 2026-10-25

Scope is repository separation. No mechanics phase, numerical change, persistence
migration or feature reactivation is part of this task. See
[pre-change audit](validation/MIGRATION_AUDIT.md) and
[validation results](validation/MIGRATION_RESULTS.md).

## Layout

```text
Projects/
├── line_solver/                         independent Python project, name retained
│   ├── src/, tests/, data/, specifications/
│   ├── scripts/android_reference/         optional Python oracle/workbook tools
│   └── android/                           historical/private local evidence only
│       ├── validation/                    all original personal evidence retained
│       └── doc_archive/                   original historical documents retained
└── line-solver-android/                    independent local Git repository
    ├── app/, catalogue/, reference/, data/materials/
    ├── specifications/, branding/, deferred/, doc_archive/
    ├── gradle/, gradlew, settings.gradle.kts, build.gradle.kts
    └── README.md, data/SNAPSHOT.json, validation/MIGRATION_*.md
```

Original IDE/cache/local SDK configuration also remains local in the old evidence
directory. It is not part of the new repository. The Python project was not renamed.

## Files and dependency removal

Moved the portable Android application sources/resources/tests, Gradle wrapper
and configuration, catalogue, reference JSON, branding and deferred snapshots to
the sibling project. Copied Android historical documents unchanged and portable
format specifications from the Python project. The original archive is retained.
Copied three required inputs from their verified existing data/materials paths:
material model JSON, webbings.json, and
phase6_connector_research/connector_specs_research.json.

Changed app/build.gradle.kts to use root-project-local material input and pass the
Android root to tests and the native catalogue generator. Updated old android/
prefixes only in nine test/generator source files, attribution notices and current
documentation. Optional report replay still accepts an explicit user-provided path.
Normal regressions require only tracked local data. Set the already-required
one-worker/1 GiB/in-process compiler and 384 MiB test heap limits in project config;
no external validation init script is needed.

The original Python exporter/audit/helper tools are retained in the Python project
under scripts/android_reference. Export/audit commands require --android-root;
they do not guess a sibling location. Their numerical algorithms are unchanged.
Their existing output JSON and generated Kotlin metadata are retained unchanged.
They are never invoked by Android Gradle tasks. Mainstream catalogue generation
remains Kotlin/JVM and reproduces its three generated artifacts exactly.

Added standalone README, development/handoff/scope docs, data snapshot/update
policy, reference organization notes, migration audit/results and Git ignore rules.
Personal validation files, exports, local settings, keys and caches are excluded;
only the two reviewed migration documents are allowed under validation by default.
Required catalogue/reference files and the executable wrapper/JAR are staged.

## Data and compatibility

Material source and destination SHA-256:
`57de2e9012dde5522e1080e3de239da1ade70ab2c14f98a3d2c185324f3b5edf`.
All three input copies match their sources byte for byte; all original reference
JSON, saved-format test resources, catalogue outputs and reconciliation rules
are unchanged. data/SNAPSHOT.json records all input hashes, source paths and date.
No source commit could be recorded because the original workspace's .git metadata
is unusable. Historical oracle manifest hashes remain historical evidence.

All runtime Kotlin sources, manifest, numerical laws and storage implementations
are byte-identical. Only asset licence notice paths change in production resources;
licence content and data hashes are preserved. applicationId/namespace remain
org.linesolver.base, versionCode 1 / versionName 0.1.0. No signing configuration,
key, schema, preference, material ID, revision or rig format is changed. No APK is
installed on a phone. Original signing identity must be retained privately for
future in-place updates, subject to Android's normal version requirements.

## Verification and limitations

See validation/MIGRATION_RESULTS.md for exact task sets and observed outcomes.
Fresh baseline: 205 JVM tests, 204 passes, one expected optional report-replay skip.
Python material regression checks: 30 passes. Python source/tests/production data
remain hash-identical. Checked-in solver/material fixtures and numerical assertion
tolerances are unchanged. This demonstrates preserved tested numerical behavior;
it does not extend experimental validation or claim new-feature Python parity.

The independence check uses only staged files exported through git checkout-index,
with no local.properties/build outputs/caches in the checkout. A Bubblewrap mount
namespace hides the entire original Projects directory. Only the installed SDK,
JDK, dependency caches and original local debug-signing directory are available.
The Python checkout is confirmed absent inside the namespace before Gradle runs.
The run is offline, using existing Maven/Gradle artifacts. A first-time online
bootstrap on a different computer is documented but was not exercised.

Early isolation setup attempts failed because a root mount point and then the
Android debug-signing directory were read-only. The mount setup was corrected;
these did not require any source or signing-identity change. No assertions were
weakened. Existing source/API deprecations and historical archive/licence whitespace
are preserved. No device interaction or fresh physical-device test is needed for
this source-layout-only change; instrumentation sources are built separately.

## Git and remaining manual work

A new local Git repository is initialized on main. Publication files are staged
and reviewed for machine-local/device paths, signing material and common credential
signatures. No credentials, keys or personal validation exports are staged. No
commit, remote, GitHub repository, push or publication is created automatically.

Review the staged initial tree, choose any project licensing policy separately,
then commit and configure/publish a remote when authorized. Supply the original
release signing key outside Git and increment versionCode for a future release.
The original Python workspace has no usable Git status/history; it is otherwise
preserved, including all personal validation data. No runtime/build/test dependency
on its presence remains in Android.
