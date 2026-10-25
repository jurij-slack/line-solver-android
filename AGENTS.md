# Android repository working instructions

Read PROGRESS.md and ROADMAP.md before changes. Implement only the current user
scope; historical doc_archive roadmaps do not authorize new phases. Do not edit
that archive. Inspect Git status and preserve unrelated local changes.

The Android app, solver, catalogue generation and JVM tests are Kotlin-native.
No Python runtime, build or test dependency is permitted. The independent Python
project is the authority for material development. Follow data/README.md for
explicit snapshot updates; never add an automatic neighbor-project dependency.

Preserve numerical laws, material/model IDs and fingerprints, saved rigs, topology,
storage identities and app signing/update compatibility unless explicitly tasked
otherwise. Preserve existing Simple/Mixed behavior and temporary feature gates.
Do not infer new mechanics from historical documentation.

Run relevant JVM regressions, assembleDebug and lintDebug for implementation
changes. Record exact blockers and results; do not loosen assertions to pass.
Never commit local.properties, keys, passwords, device backups or personal exports.
Do not install/uninstall/clear a user's device app without verified preservation
of its existing private files/preferences. No commits or publication unless asked.
