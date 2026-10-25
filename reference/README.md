# Checked-in reference evidence

JSON files are retained byte-for-byte from the pre-migration Android project.
They record historical Python oracle and material-audit results. The provenance
manifest names Python source files by relative path and hash; Android never opens
those Python paths. Native JVM tests compare numerical outputs against these files.
Additional analytical and saved-format fixtures live in app/src/test/resources.

Regenerating evidence requires a separately reviewed change using the independent
Python project's scripts/android_reference tools with an explicit --android-root.
Ordinary Android builds, tests and catalogue generation require no Python.
