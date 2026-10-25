# Line Solver App — Simple Mode Roadmap

**Status:** Phase 0/1 plus independent-support follow-up and Phase 2 verified; Phase 3 verified; Phase 4 verified; Phase 5 verified; Phase 6 verified; Phase 7 verified; Phase 8 implemented and verified; sliding untouched  
**Focus:** Physically consistent mainline-failure calculations, convenient rig configuration, and reproducible debugging  
**Source of truth:** The existing repository, its physics implementation, tests, and the original Python app. Do not assume the present code matches this document.

## 1. Objectives

Improve Simple Mode without turning it into an unnecessarily complex engineering interface. The application should:

1. Model a **leash** as a walker-to-line connection, **not** a structural split connection.
2. Account for the gravitational potential-energy change from a walker **initially standing on the mainline** to the post-failure, leash-supported configuration.
3. Provide reproducible diagnostics and an exportable report when calculations fail or violate physical checks.
4. Support rapid creation of uniform rigs and conversion to individually editable mixed rigs.
5. Persist walker defaults, advanced solver settings, and named rig configurations appropriately.
6. Provide an overlay plotter for selectable **force–elongation curves** of webbing and bungee materials, explicitly distinguishing supplied-data and extrapolated ranges.
7. Explain the model's assumptions and limitations before the user first uses it.
8. Leave the architecture ready for **future sliding/contact mechanics**; do not attempt that feature now.

**Safety constraint:** This is an unverified simplified model. Results must not be presented as proven bounds on real-world sag, forces, or safety. Never silently hide solver failures, invalid input, or failed physical-consistency checks.

## 2. Working rules for Codex

- **Inspect before editing.** Locate and read the relevant Simple Mode UI, core solver, original Python uniform-section generator, reference models, existing tests, and data-persistence code. Document the actual structure briefly before choosing an implementation.
- **Prefer one physics implementation.** Reuse or extract the validated calculation logic rather than maintaining independently diverging formulas in the UI and Python reference app.
- Preserve functionality and file formats unless an explicitly documented migration is necessary.
- Separate **physical rig data**, **walker data**, **solver settings**, **solver results**, and **debug metadata**. Avoid duplicating fields across screens and models.
- Implement small, reviewable changes with unit and integration/regression tests. Use deterministic test inputs and tolerances appropriate to the numerical method.
- Document units and coordinate/sign conventions for every physical quantity. Use SI internally unless there is an established and justified convention.
- Do not invent material curves, elastic parameters, or empirical validation. Preserve provenance of material data.
- Material curves used for display and those used by the solver must derive from the same underlying model/data; any plotting interpolation or extrapolation should be clearly identified.
- Never silently reinterpret old saved configurations. Provide explicit backward-compatible conversion or a clear migration strategy.
- Update this roadmap's phase checklist as work progresses. Record assumptions, limitations, and relevant commands in documentation.
- If repository inspection contradicts an assumption in this roadmap, explain the discrepancy and implement the smallest physically justified correction.

## 3. Definitions and conceptual model

- **Mainline:** primary load-carrying webbing before failure.
- **Backup:** system that takes the fall load after mainline failure, subject to existing topology and material models.
- **Split connection:** actual connection between structural webbing sections/line systems. It can impose connectivity and possibly stopping/contact conditions as explicitly modeled.
- **Leash:** a walker-to-line attachment, which may be initially slack and then become taut. It **does not** create a structural split or terminate a section by itself. A computational node may be inserted for applying a local load, without changing structural continuity.
- **Standing COM height:** the vertical offset of the walker's center of mass (COM) above the local mainline level in the initial standing configuration.
- **Leash length:** define precisely against the current UI/data model. Prefer a clearly defined **effective attachment-to-COM length** for the energy model; if the UI stores physical leash-to-harness length, represent the harness-to-COM offset explicitly instead of silently equating the two. Document the definition in the UI and model; retain old values safely.
- **Uniform rig:** one generator setup expanded into N equal or automatically determined sections using the original Python app's established rules for sections, backup-extra length, materials, and split connections.
- **Mixed rig:** explicit per-section representation; may originate from a uniform generator.
- **Force–elongation curve:** tensile force (default display unit **kN**) as a function of **relative elongation (%)**, with relative elongation defined as `100 * (L - L0) / L0` for an explicitly defined reference length `L0`. For material comparison, do not use stress (MPa) or a stress–strain label.
- **Material curve data domain:** the relative-elongation interval for which tabulated source values, or the documented valid domain of a supplied fit, exist. Beyond that domain the model response is **extrapolated**, even if the solver can evaluate it. Connecting or fitting points within a measured data interval is **interpolation/modeling**, not evidence that every intermediate point was measured.
- **Provenance:** keep original supplied points, fitted/interpolated representations, and extrapolated continuations conceptually distinct. Record the raw data extent, source, and extrapolation rule per material where available.

### Energy-model requirement

For a walker starting on the mainline, calculate the gravitational contribution from actual initial and final COM elevations in one consistent reference frame:

\[
\Delta E_g = mg\,(z_{\mathrm{COM},0} - z_{\mathrm{COM},f}).
\]

The initial standing COM height **and** the distance available as the leash goes taut must enter the calculation. The final backup sag influences the final COM elevation and must therefore be included consistently. Do **not** independently add `mg * (standing_height + leash_length)` if the same vertical drop is already represented through final geometry. Respect initial/final elastic energies and any explicitly modeled work/dissipation.

Use the application's actual geometry (attachment coordinates, sag, leash engagement, and COM offsets) rather than assuming every distance is automatically vertical. The solver estimates a maximum deflection **under its stated assumptions**; it does not simulate all transient behavior and does not guarantee a real-world upper bound.

## 4. Implementation phases

### Phase 0 — Audit, baseline, and executable reference cases

**Goal:** Establish what the current Simple Mode actually does before refactoring.

- [x] Locate Simple Mode UI, computational backend/physics code, original Python generator, current tests, and storage.
- [x] Trace the full calculation path: input -> rig topology -> failure scenario -> energy equation -> numerical solver -> display.
- [ ] Reproduce and document the two reported problems: leash acting like a split, and walker not using standing-to-hanging gravitational drop.
- [x] Add deterministic baseline tests/fixtures for existing working scenarios, including no-failure and failure cases where available.
- [x] Identify existing numerical tolerances, sign conventions, material interpolation behavior, and failure modes.
- [x] Produce a concise architecture/assumptions note for later implementation phases.

**Audit note (2026-10-08):** See [Phase 0/1 physics audit](PHASE01_PHYSICS.md).
The unchecked reproduction item is deliberate: an interior attachment already
preserved continuity and was not a structural split; the catch already included
standing drop. A separate **776.952 J standing-state reporting discrepancy** was
reproduced and corrected. The three pre-fix regressions were 2 pass / 1 fail.
Do not invent a physical topology bug to close this checkbox.

**Done when:** Known behavior is reproducible, bug cases fail before their fixes, and the planned code changes are tied to actual files/classes/functions.

### Phase 1 — Correct leash connectivity and walker fall energy (P0)

**Goal:** Fix the physical model before further UI development.

- [x] Model leash attachment separately from structural split connections throughout input parsing, topology, solver, and output.
- [x] Preserve webbing continuity through the walker attachment; keep real split connections working.
- [x] Define and document walker position, initial standing COM height, leash-length convention, and their coordinate reference.
- [x] Support **standing on mainline before failure** as the normal Simple Mode initial condition; do not assume the walker begins suspended by a taut leash.
- [x] Represent slack/engagement of the leash consistently with the model and solve for the final geometry at maximum deflection.
- [x] Include gravitational energy using initial/final COM positions, consistently with elastic energy, pretension, backup sag, and reference elevations.
- [x] Reject or clearly flag impossible geometry, negative/invalid lengths, and solver nonconvergence.
- [ ] Preserve a separately selectable initially-hanging scenario only if one already exists or it can be added without scope expansion; do not make it the default.
- [x] Test for sensitivity to standing height and leash length, continuity at leash engagement, consistent zero-drop/limiting cases, and no accidental double counting of sag.
- [x] Where possible, compare to reference Python results for unaffected scenarios.

**Implementation note (2026-10-08):** Explicit attachment/posture/person geometry
and one standing/catch ledger are implemented; all 18 JVM tests pass. Existing
Python force/geometry regressions remain within their original tolerances. The
initially-hanging option above is **not applicable**: none existed, and it was not
added. The common fixed ring still collocates both lines under the existing model;
no sliding, impact-dynamics or independent slack-ring model is implied.

**Done when:** The leash no longer behaves as a structural split, the standing-to-hanging energy budget is correct under documented assumptions, and regression tests pass.

### Phase 2 — Diagnostic reports and export (P0)

**Goal:** Make errors reproducible and actionable by developers and Codex.

- [x] Capture failures from input validation, topology generation, numerical solving, material interpolation, and physical-consistency checks.
- [x] Generate a versioned, machine-readable **JSON diagnostic report** containing, where available:
  - report schema version, app/solver version (and Git commit when available), timestamp, calculation identifier;
  - rig topology, section data, chosen materials and material-curve identifiers or reproducible snapshots;
  - walker inputs, failure location/scenario, solver settings, units;
  - initial/final energy terms, residuals, iteration counts/history or bounded summaries, last valid state;
  - exception type/message and sanitized traceback, warnings, failed checks.
- [x] Provide **Save/Export Debug Report** through a normal platform-appropriate file-save/share mechanism. No automatic uploading, sending, or background telemetry.
- [x] Keep exported reports free of passwords, tokens, private filesystem paths where feasible, and irrelevant personal information.
- [x] Add automated tests that validate JSON serialization and reproduction of a representative failed run from exported inputs.
- [x] If technically practical, allow an optional report export for successful runs to compare numerical diagnostics.

**Verification note (2026-10-08):** Phase 2 build/lint and all 31 active JVM tests
pass. The connected phone verified Standing/Catch/Rest and system-picker saves for
success and failure. Both actual phone files passed strict JSON parsing and JVM
replay. See [evidence](validation/phase02/REPORT.md).

**Done when:** A saved file plus repository source is sufficient to reproduce at least one tested failure case, and no report is sent without user action.

### Phase 3 — Clean Walker section and persistent solver settings (P1)

**Goal:** Make main-screen inputs coherent and reduce repeated typing.

- [x] Consolidate **all walker-related inputs** into a single **Walker** section in Simple Mode, avoiding duplicate controls.
- [x] Persist user defaults locally for mass, standing COM height, leash information, and other walker-specific parameters.
- [x] Persist walker reference position as explicitly requested for Phase 3; revalidate it against the current rig without silently clamping solver inputs.
- [x] Remove gravity `g` and numerical controls from the main screen. Put them in a **Solver Settings** view/menu.
- [x] Persist changed solver settings across restarts and provide **Restore Defaults**.
- [x] Document defaults, validation ranges, and units; ensure changes propagate to the same computational model used by tests.

**Implementation note (2026-10-08):** Walker fields share one section; the gear
button opens global Solver Settings. Two separate SharedPreferences records retain
walker/model values independently of the activity rig draft, seeded compatibly from
legacy state. Invalid solver edits are not applied; Restore Defaults resets gravity,
mesh and Advanced resistance. The unchanged Draft feeds the solver and diagnostics.
38 active JVM tests pass; phone tests verify edits, recreation, force-stop/relaunch
persistence and defaults. See [Phase 3 evidence](validation/phase03/REPORT.md).
No Phase 4+ features were added.

**Done when:** A restart restores walker defaults and solver settings while the main screen remains focused on rig and walker setup.

### Phase 4 — Named rig library (P1)

**Goal:** Make experimental setups repeatable.

- [x] Save a rig with a user-defined name; list, load, rename, duplicate, overwrite (with confirmation), and delete saved rigs.
- [x] Store span/geometry, mainline and backup material selections, section topology and lengths, backup-extra length, pretension, and other rig-specific parameters.
- [x] Keep global walker profile and solver settings independent of the saved rig by default. Explicitly label any included scenario override.
- [x] Use schema versioning and validation; handle old saved data safely.
- [x] Distinguish current unsaved edits from the last saved version.

**Implementation note (2026-10-09):** Save Rig and the searchable Saved Rigs
library support custom names, explicit update confirmation, save-as-new, rename,
duplicate and confirmed delete. Stable UUID records use app-private atomic JSON
(schema 1). Rig-only mapping preserves all sections, material IDs, lengths, units,
connections and prescribed-cut settings; walker position and global preferences
are never loaded from the library. Unsaved rig changes require confirmation before
loading. Missing/changed materials and unsupported libraries are not substituted
or overwritten. See [Phase 4 validation](validation/phase04/REPORT.md).

**Done when:** A mixed or uniform-generated rig can be saved, reloaded, and solved with an identical reconstructed topology.

### Phase 5 — Uniform generator and convert-to-Mixed workflow (P1)

**Goal:** Restore the original Python app's convenient uniform-section construction.

- [x] Inspect and reuse the original Python uniform-section generator's actual behavior rather than inventing incompatible formulas.
- [x] Accept total length/span, number of sections, uniform mainline/backup materials, backup-extra length (absolute or percent where supported), pretension, and relevant split settings.
- [x] Generate consistent section boundaries and internal split connections; validate N=1 and N>1.
- [x] Show an understandable section summary/preview with derived per-section values.
- [x] Add **Edit Individual Sections**: convert the generated uniform setup into an explicit Mixed configuration with all physical inputs preserved.
- [x] Make conversion one-way until explicitly regenerated, or otherwise guard against overwriting Mixed edits when returning to Uniform.
- [x] Add round-trip/physics-equivalence tests: Uniform solve = equivalent untouched Mixed solve, within solver tolerances.

**Done when:** A five-section uniform rig is generated correctly and can be edited as a Mixed rig without changing its initial physical configuration.

Implementation and verification: [Phase 5 report](validation/phase05/REPORT.md).
One recipe derives equal prepared sections; metre extra is a total shared across
sections, percent a local ratio. Guarded conversion preserves physical inputs.
Schema 2 saves generator recipes and explicit Mixed sections; schema 1 rigs retain
legacy semantics. Stop before Phase 6.

### Phase 6 — Force–elongation curve explorer and provenance-aware overlay plotter (P1)

**Goal:** Let users inspect and compare the material behavior on which the solver relies, including the exact transition from supplied source data to model extrapolation.

- [x] Add a **Force–Elongation Curves** view accessible from the material picker and/or a secondary navigation entry; do not crowd the Simple Mode calculation form.
- [x] List **all available webbing/bungee material definitions** from the app's actual material registry, with a small **checkbox for each curve**. Allow any number of simultaneous selections (including one or none), with convenient **Select All / Clear All** actions for larger libraries.
- [x] Overlay the selected curves in a single plot using distinct, stable colors and a legend mapping each color to the corresponding material. Support zoom/pan/hover or equivalent mobile-friendly inspection if supported by the app's plot framework.
- [x] Standardize the chart axes: **x = relative elongation (%)**, **y = tensile force (kN)**. Express the material data in these units through documented conversions and reference-length conventions; do not silently treat absolute elongation in mm or m as a percentage. Do **not** offer a stress–strain plotting mode as part of this feature.
- [x] Show the **provided-data domain** for **each individual material curve**, not one global cutoff: **solid line** for the model within the supplied/valid range, **dashed or semi-transparent line** for extrapolated continuation, and **boundary markers** at the minimum and maximum of the supported domain where applicable. If source data start above zero, the earlier region must also be visibly extrapolated.
- [x] Provide an understandable legend or compact on-screen explanation of line styles (**provided data range** versus **extrapolated model**). If using shading, ensure overlapping selections do not make provenance ambiguous; per-curve style is authoritative.
- [x] Display actual supplied data points with markers when available; distinguish raw points from interpolated/fitted curves. For supplied fits without raw samples, label the source appropriately rather than implying measurements at every curve location. Expose each material's measured/tabulated domain limits, data source, and curve/extrapolation method in a legend, tooltip, or details panel.
- [x] Reuse the **same material model, units, interpolation, extrapolation rules, and parameter values as the solver**, rather than introducing a visually smooth but numerically inconsistent plotting-only approximation. Clearly identify the extrapolation method where possible.
- [x] Do not silently extend curves past physically meaningful or solver-supported ranges. Define a documented plotting limit with a warning if the extrapolated range is uncertain; display non-finite/discontinuous data as invalid rather than drawing a misleading continuous line.
- [x] If the solver operates at a point outside a material's supplied-data domain, expose that fact near the calculation result as a warning, not just in this plotter. This should be informational, not automatically a validation claim or a silent solver failure.
- [x] Keep curve selection a **viewing preference** independent of the rig's selected mainline/backup material. Toggling a checkbox must never modify the solved rig.
- [x] Add tests for checkbox selection/multi-curve overlay, x/y units and labels, source-domain boundaries, measured/interpolated vs extrapolated rendering, plot/solver numerical consistency, and warnings when a solved state leaves the provided data range.

**Done when:** Users can compare multiple **force–elongation curves** on one chart, determine at a glance **which portions are backed by supplied data and which are extrapolated**, and see the same material response used by the solver. No stress–strain labels, invented data, or unmarked extrapolation are displayed.

Implementation/evidence: [Phase 6 validation](validation/phase06/REPORT.md).
All 59 current model variants are discoverable; no separate material list or plotting
law. Supplied regions, imposed origins and bounded continuation retain distinct
provenance. Min/max range controls provide zoom; gestures/hover are not implemented.
Stop before Phase 7.

### Phase 7 — Every-launch safety notice and About page (P1)

**Current requirement:** The user's Phase 7 instructions supersede the earlier
persistent first-launch acceptance plan. Require acknowledgment on every fresh app
session; never persist acceptance to preferences or saved instance state.

- [x] Show the shared safety notice before solver access, with **Accept and Continue**.
- [x] Fresh root-task launches and process restarts require acknowledgment again.
- [x] Ordinary navigation and activity recreation retain acceptance within the session.
- [x] Back cannot bypass the startup gate.
- [x] More → About includes limitations, material-data collaboration and full safety text.
- [x] Show installed app version; configurable developer contact/name resources remain
  empty until verified information is supplied. No contact address invented.
- [x] Add tests for gate access, Back, navigation, recreation, process/root-task restart,
  version and configured contact display.

Implementation/evidence: [Phase 7 validation](validation/phase07/REPORT.md).
Next work is comprehensive app review, bug fixing and UI refinement, as requested.
Stop here; do not begin sliding mechanics.


### Phase 8 — UI refinement and mechanical corrections (verified)

- [x] Intact threaded main/backup contact included in the fixed-label ring solve;
  same-section prescribed main release, with load-sharing/slack regressions.
- [x] Walker sliders beneath plots; standing recalculation on release.
- [x] Confirmed tap/numerical section failure selection and cached Reset Failure.
- [x] Compact physical results, units and separate Detailed Results / JSON export.
- [x] 100-section Uniform generator; sparse Newton solve and bounded Mixed editor.
- [x] Independent Mixed access and confirmed Reset Rig preserving library/preferences.
- [x] Illustrative main sag and deterministic downward backup loops.
- [x] 74 active JVM regressions, external phone-report replay, APK/lint and all
  13 phone workflows pass; extended portrait/100-section UI check passes.

Evidence and assumptions: [Phase 8 validation](validation/phase08/REPORT.md).
Stop after Phase 8. Do not begin sliding or unrelated features.

### Future — Sliding mechanics (not part of Phase 8)

**Goal:** Extend the model to allow walker/leash attachment movement and contact behavior under explicit assumptions.

- [ ] Design movement of leash attachment relative to webbing and structural split connections.
- [ ] Define stopping constraints and whether/where the attachment can pass through connections.
- [ ] Define friction and dissipated work before changing conservation equations.
- [ ] Identify whether quasi-static energy methods remain sufficient or time-dependent simulation is required for specific questions.
- [ ] Add limiting-case, convergence, and benchmark tests before enabling by default.

**Done when:** A separately reviewed, validated model exists with documented applicability. No commitment to exact output before the mechanics are specified.

## 5. Proposed UI organization (target state)

**Main / Simple Mode**

1. **Rig:** saved-rig picker, name, save/duplicate actions.
2. **Rig setup:** Uniform or Mixed. Uniform has an N-section generator, preview, and **Edit Individual Sections** button.
3. **Walker:** mass, standing COM height, leash geometry/length, and walker position (with a line-position control where suitable).
4. **Failure scenario and Solve:** inputs relevant to the failure, calculate action, result/warning display.

**Secondary views:** Solver Settings, Rig Library management (if more space needed), **Force–Elongation Curves (with per-material checkboxes and multi-curve overlay)**, About / Safety Information, and Debug Report export on failures.

Avoid duplicated inputs, unnecessary controls in the main calculation form, and hidden automatic alterations of rig topology.

## 6. Test and acceptance matrix

| Case | Expected invariant / behavior |
| --- | --- |
| Walker attachment inside continuous webbing section | No structural split appears at the leash attachment |
| Real split connection elsewhere | Existing mechanical connection remains intact |
| Standing vs initially hanging (if supported) | Initial gravitational energy budget is different and documented |
| Change standing COM height | Gravitational energy updates consistently with geometry |
| Change leash length | Engagement/final COM geometry and energy change consistently |
| Change sag | No double counting of sag in the gravitational drop |
| Zero or limiting geometry | Defined result or clear validation failure, not a silent invalid solution |
| Solver nonconvergence | Error state + actionable local JSON debug export |
| Uniform N=1 | One section with no spurious internal splits |
| Uniform N=5 -> Mixed | Identical topology, materials, lengths, and baseline result before edits |
| Save/reload rig | Topology and numerical inputs remain equivalent |
| Restart application | Persistent walker defaults/settings are restored |
| First launch | Disclaimer requires acknowledgment and is accessible afterwards |
| Material data missing/out of valid range | Explain the limitation; do not fabricate a curve or extrapolate silently |
| Plot two or more material curves | Correct overlays and readable per-material identification; toggles do not change rig input |
| Different supplied-data elongation domains | Per-curve transition points are marked; supplied-range segments solid and extrapolated parts dashed |
| Compare material units | X-axis is relative elongation (%), y-axis is force (kN); no stress conversion |
| Solver crosses a supplied-data boundary | Visible warning on the result, not a silent extrapolation |

## 7. Completion and release gates

- [x] Tests relevant to each implemented phase pass, with failures and skips reported explicitly.
- [ ] Manual smoke test of Simple Mode with at least one uniform and one mixed configuration.
- [x] Documented model conventions and known limitations match the actual implementation.
- [x] Exported diagnostic report can be opened by a developer and used to replay at least one test scenario.
- [ ] Force–elongation comparison graph correctly distinguishes source-data domains from extrapolation and matches the solver model.
- [ ] Disclaimer is shown and acknowledged before general release.
- [x] No claim that the solver is experimentally verified or a guaranteed safety predictor.
- [x] Sliding mechanics remain disabled/unimplemented until separately reviewed and tested.

## 8. Suggested Codex delivery format for each phase

At the end of each phase, report:

1. Files inspected, relevant architecture, and root cause(s).
2. Changes made (with key files).
3. Tests added or updated, commands run, and pass/fail/skip results.
4. Numerical/physical assumptions, known limitations, and any migrations.
5. Any follow-up required, plus checklist changes in this `ROADMAP.md`.

Start with the separate **Phase 0/1 Codex prompt** supplied alongside this roadmap. Do not begin sliding mechanics during these phases.


### Completed Phase 8 follow-up — material completeness audit

All runtime material variants and retained source catalog records are audited in
[MATERIAL_DATABASE_AUDIT.md](MATERIAL_DATABASE_AUDIT.md), with a complete JSON
inventory and prioritized missing-data research list. Existing supported bounded
continuations and integrated energy agree with Python; missing MBS/mass are not
estimated. Kotlin metadata/endpoint validation, supplied-origin provenance and
percent endpoint plotting were corrected. 79 active JVM tests and APK/lint pass.
No sliding or unrelated roadmap work was added.

### Completed material database update — 2026-10-10

- [x] Reconcile all research revisions and retain product/batch/termination provenance.
- [x] Fill supported runtime masses/strengths, including developer Plasma/Rodeo masses.
- [x] Add explicit bounded provisional linear Jumanji and three Sigma models.
- [x] Restore Dyneemite PRO continuation with the correct PRO/19 mm 34 kN metadata.
- [x] Preserve IDs/source knots and confirm known saved-rig material revisions.
- [x] Regenerate audit/inventory and Python/Kotlin numerical oracles.
- [x] Build/lint, regressions and installed-phone curve explorer checks.

63 runnable variants, zero missing masses, one unassigned historical-curve MBS.
Batch and termination limits are not silently transferred. Missing curves, ReBL
reconfirmation, Funky strength conflict and termination-selection work remain
explicitly documented in the [update report](validation/material_update/REPORT.md).
No subsequent roadmap phase or sliding mechanics started.
