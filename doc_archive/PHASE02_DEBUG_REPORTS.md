# Phase 2 — debug reports and remaining visualization fixes

> Phase 8 update: the historical Phase 2 mechanics below used backup-only failed
> support. Current reports use `simple-threaded-ring-3`, retain intact main/backup
> ideal ring contacts and include per-cable threading/reactions. Separate node IDs
> remain; the contact constraint is not a structural split. See the
> [Phase 8 report](validation/phase08/REPORT.md). Old solver-version reports require
> their original implementation for replay.


2026-10-08. Implementation and JVM verification complete; APK/lint and physical
phone/file-picker verification remain blocked in this session. See
[validation evidence](validation/phase02/REPORT.md). No later roadmap phase started.

## Initial state and visualization correction

Part A was already implemented. `PHASE01_FOLLOWUP.md` supersedes the original
shared-ring conclusions: interior main and backup attachment nodes are independent;
Standing loads main, Catch/Rest load backup, and only actual section connections
share DOFs. No physics correction was necessary for the remaining rendering issue.
The diagnostic runner and solver trace existed as unfinished, unwired source.

Removed the magnified walker detail. `RigDrawing` draws the normal full-span cables,
ring, vertical leash and body using `walkerDrawing()` and a single equal-axis
metre-to-pixel transform. Bounds include the whole body. The harness and COM are
the solver's coordinates, not screen offsets. Equilibrium-only runs without body
height retain the existing symbolic walker; no body dimensions are invented.

`backupThreading()` returns short portions of the two actual backup edges incident
to the supporting attachment. These are drawn solid through a hollow ring so a
dash gap cannot make the backup look disconnected. Endpoints remain on the solved
segments; no fake bridge joins main and backup. The ring glyph is 5 dp in radius;
body/leash positions and lengths use the metre scale. Standing retains independent
backup sag; Catch and Rest share the same drawing path.

## Architecture

- `diagnostics/DebugReport.kt`: `DiagnosticRunner.run` validates the original Draft,
  runs `SimpleSolver`, captures exceptions and constructs schema-v1 JSON for both
  success and failure. Cancellation is propagated, not reported as failure.
- `solver/SolverDiagnostics.kt`: serializable settings and an observational trace.
  Keeps at most 64 iteration records, named completed states, last accepted
  equilibrium, last trial, installed lengths/pretension and the catch bracket.
  It does not change convergence acceptance or the physical model.
- `SetupViewModel`: uses that runner, keeps reports with their calculation, cancels
  stale work on editing, and writes user-requested exports on the IO dispatcher.
- `BaseScreen`: concise failure message plus **Save Debug Report**, and an
  **Export Diagnostics** option for success. **Diagnose invalid inputs** captures
  a currently invalid form without enabling the normal Solve button.
- `writeDebugReport`: writes UTF-8, flushes/closes the SAF stream, and returns a
  failure for null streams, revoked permission, write/flush/close errors.

Editing clears the previous result/report. An export already launched retains a
snapshot in the ViewModel so editing/rotation cannot silently change that file.
Cancelling the picker writes nothing. Reports are not persisted across process
death; recalculate if Android kills the process. No upload, telemetry, network
permission or share/email action was added.

## JSON schema and units

Top-level fields:

| Field | Contents |
|---|---|
| `schema_version` | Integer `1`; unknown versions rejected by replay |
| `report_id`, `timestamp` | UUID and UTC ISO-8601 timestamp |
| `app_version`, `solver_version` | Installed version; `simple-independent-support-2` implementation identifier |
| `calculation_status`, `failure` | SUCCESS/FAILED; category, stage, exception, sanitized message and bounded application stack |
| `rig_configuration` | Complete serialized Draft, installed main lengths and achieved pretension when available, failure model |
| `walker_configuration` | Original mass/reference position/body height/leash strings and body geometry fractions |
| `solver_settings` | Force/energy tolerances, Newton and catch iteration limits, catch step |
| `material_data` | Selected IDs and complete original model metadata, points, density, MBS and continuation inputs |
| `connection_topology` | Independent fixed labels; only physical splits share DOFs; sliding/policies inactive |
| `states` | Available standing/catch/rest: topology, element inventories, coordinates, body/leash, forces, strains, energy, residual and iterations |
| `energy_ledger` | Phase 1 initial/turning energy, residual, walker/webbing gravity release, elastic increase, modeled dissipation and relaxation loss |
| `numerical_diagnostics` | Stage, reason, bounded iteration history, catch bracket, last accepted state and clearly labelled unconverged trial |
| `visualization_state`, `warnings` | Selected plot state and numerical/material/clearance warnings |

SI results: metres, kilograms, newtons, joules and seconds; y positive upward from
the original anchor datum. Coordinates are `[x,y]`. Node/section indices are zero
based. `reference_coordinate_m` is a material coordinate, not horizontal x.
Force maxima are **spatial maxima in the named state**, not transient/history peaks.
Free-force residuals omit explicitly constrained coordinates during catch search.

Draft fields deliberately preserve the original input strings, including invalid
values: `pretensionKn` is kN, `extra` follows `extraUnit` (percent or metres), gravity
is m/s², other dimensions are SI. Gravity and element count are in the Draft.
Backup reference length follows the existing installed-main/extra-ratio rule;
there is no independent backup-pretension input to invent.

Numerical NaN and infinities use JSON strings `"NaN"`, `"Infinity"`, `"-Infinity"`;
unavailable data is `null`. No bare non-standard numeric literals are exported.
A failed trial can have unavailable strict material elastic components while its
trial total is retained. Completed state energies and the Phase 1 ledger are
unmodified. Failure before catch leaves the final ledger unavailable.

Material snapshots rebuild the same tension-only PCHIP and analytic integral, with
the existing bounded linear continuation to MBS. The runtime continuation rule is
explicit alongside source metadata (whose historical extrapolation-policy field
may say `error`). No database/provenance data was changed. JSON number formatting
may normalize scientific notation; double values and curve evaluations round-trip.

## Failure types

`INVALID_CONFIGURATION`, `NON_CONVERGENCE`, `ROOT_FINDING`, `NON_FINITE`,
`MATERIAL_EVALUATION`, `MATERIAL_LIMIT`, `ENERGY_CONSISTENCY`, `UNSUPPORTED_MODEL`,
`UNEXPECTED_EXCEPTION`. Invalid form/settings, Newton exhaustion/failure, missing
catch brackets, failed energy roots, invalid numerical evaluations, curve
exceptions and accepted-state material limits remain distinguishable. A material
limit is a model boundary, not a simulated rupture event. High but finite valid
forces or unusual geometry are not rejected by diagnostics. Extrapolation within
the supported material domain remains a warning, not a new failure.

## Export on Android

1. Solve normally; select Standing, Catch or Rest if comparing a geometry issue.
2. Tap **Export Diagnostics**, or **Save Debug Report** after failure.
3. Choose a local destination in the Android system picker and save
   `linesolver_debug_YYYYMMDD_HHMMSS.json`.
4. Wait for **Debug report saved.** If saving fails, select another location and
   retry. The original report remains available. The app works offline; choose a
   local provider when offline.

Picker launch/cancellation/rotation and real provider writes still need physical
device verification in this session; stream failures are covered by JVM tests.

## Developer reproduction

Copy the exported JSON into `android/validation/phase02/` (or another local directory)
and run from `android/`:

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew --max-workers=1 testDebugUnitTest \
  --tests org.linesolver.base.ReplayReportTest \
  -PdebugReport=/absolute/path/to/report.json --rerun-tasks
```

`ReplayReportTest` writes a sibling `<name>.replayed.json` before comparison. It
reconstructs the Draft, settings and embedded material repository, invokes the same
runner used by the app, compares status and failure category/stage, and compares
all available state and energy data. Numeric comparison tolerance is
`1e-7 + abs(original)*1e-9`; structural/string values must match. Report identity,
timestamps and stack line numbers are intentionally excluded. A mismatch fails the
test and leaves both reports for inspection. Without the property the external-file
test is skipped; the normal diagnostics tests always perform round-trip replay.

For a regression, retain the report as a test resource, call
`DiagnosticRunner.replay(resource.readText())`, and assert the specific defective
quantity/category. After fixing a bug, update expected behavior deliberately;
do not simply accept the old failed outcome. Success and deterministic
one-iteration failure fixtures under `validation/phase02/` were both replayed.

## Limits

Replay is a full rerun, not an iterative checkpoint restart. It requires matching
solver source/version; reports do not embed Kotlin code. Git metadata was absent,
so no commit hash is fabricated. Archive the source revision with reports when
available. Fixed calibration/Newton implementation constants remain defined by
that solver version; exposed settings and the input mesh/gravity are captured.

The reader rejects documents over 2,000,000 characters, and reports retain bounded
history rather than every Newton/root step or Hessian. Typical verified reports
are 84 KB success / 23 KB failure. Errors before material selection may have no
snapshots or physical state. Advanced remains explicitly unsupported; an exported
Advanced request can reproduce that refusal, not a sliding solution. Fatal process
termination/OOM and material-asset loading before the ViewModel exists cannot
produce a report. General dynamic/experimental validity is unchanged.
