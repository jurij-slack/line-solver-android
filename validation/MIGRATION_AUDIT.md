# Standalone Android migration audit — 2026-10-25

Authorized scope: repository separation only; no roadmap physics phase is activated.
The current source workspace has no usable Git metadata (git status fails).

Before migration:
- app/build.gradle.kts copies ../data/materials/webbing_material_models_v1.json.
- catalogueProjectRoot and generator arguments point to the parent repository.
- JVM tests read parent data/materials/webbings.json and
  data/materials/phase6_connector_research/connector_specs_research.json.
- JVM tests/generator use android/catalogue, android/app and android/deferred.
- catalogue/README links to parent specifications/component-catalogue-v1.
- Python oracle exporters and workbook audit tools depend on Python source,
  scientific libraries and workbooks. These are reference-development tools,
  not Android build/test dependencies; retain them in the Python project and
  require explicit Android output paths after relocation.
- reference/*.json and app/src/test/resources contain existing numerical and
  saved-rig baselines; retain byte-for-byte, including historical provenance.
- README/DEVELOPMENT describe obsolete nested paths and old feature status.
- .gitignore only excludes Gradle/Kotlin/build, local.properties and *.jks;
  it does not protect phone backups, exports, IDE settings or other credentials.
- validation contains personal/device evidence. Preserve it at its original
  location; only explicitly reviewed, non-sensitive documentation may be copied.
- doc_archive is historical and will be copied unchanged, never edited.
- No signing configuration is defined in the build. Preserve applicationId,
  namespace, version, manifest and all runtime source/storage contracts.

Plan: copy portable sources into an isolated staging root; copy exactly three
required JSON inputs; anchor Gradle/test paths at the Android project root;
retain Kotlin catalogue generation; document snapshot hashes and updates;
initialize/stage a local Git repository with restrictive publication ignores;
validate a tracked-files-only checkout in a filesystem without the Python tree;
install the validated project as a sibling; only then remove migrated originals,
leaving personal validation data in place. No commits, remotes or device installs.
