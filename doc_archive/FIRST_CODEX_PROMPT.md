# Codex task: Phase 0/1 — audit and correct the Simple Mode physics

Read `ROADMAP.md` first and treat it as the longer-term plan. **Only implement Phase 0 and Phase 1 in this task**, with minimal diagnostic hooks if they directly help tests. Do not yet redesign the full UI, add a rig library, build the uniform generator, implement report export, or implement sliding mechanics.

## Context

The current Line Solver App Simple Mode appears to have two important modeling errors:

1. The **walker leash is being treated as a structural split connection**, although a leash is an attachment from the walker to the line and should not divide the webbing or have the stopping/contact semantics of an actual split connection.
2. On mainline failure, the model appears to assume that the walker **begins hanging in the leash**. The normal intended initial state is **standing on the mainline**, with the walker's center of mass above it. The available gravitational energy includes the initial standing COM height and the drop until the leash-supported configuration, while remaining consistent with the final backup sag and the full geometry.

## First inspect the repository

- Find the Simple Mode UI, physics solver(s), rig topology/section representation, walker/leash inputs, energy equations, the original Python app, and relevant tests.
- Trace how a user-configured rig reaches the numerical solver, where split connections are created, where a leash attaches, and where energy is calculated.
- Determine the current sign convention, dimensional units, coordinate reference, leash-length meaning, pretension/elastic-energy handling, and assumptions about initial and final states.
- Identify the source of the wrong split/leash behavior and the missing gravitational drop. Briefly summarize what you found before substantial refactoring (in your working notes or final report).
- If a baseline test suite exists, run the relevant tests before changes. Add focused regression tests that expose each reported bug.

## Implement the smallest physically consistent corrections

**A. Leash vs split**
- Represent leash attachment separately from an actual structural split connection in the calculation model.
- Preserve continuity of webbing at a walker/leash attachment; a numerical attachment/load node is fine if it does not introduce structural-split behavior.
- Preserve actual split connections and their intended constraints elsewhere.

**B. Standing-to-hanging gravitational energy**
- Make the default Simple Mode failure scenario start with the walker standing on the mainline, not already suspended from the leash.
- Define and document the precise meaning of standing COM height and leash length. If the input is physical leash-to-harness length rather than effective leash attachment-to-COM distance, handle the harness/COM offset explicitly or clearly document any existing approximation; do not silently change stored semantics.
- Use a consistent coordinate system and calculate `delta_E_gravity = mass * gravity * (initial_COM_z - final_COM_z)` using the actual initial standing and final leash-supported configurations.
- Ensure the model accounts for engagement of a slack leash, changes in backup sag, stored elastic energies, and existing model constraints. Do not double count drop due to sag or add a geometry-independent `m*g*(h+L)` correction on top of the same drop.
- Do not pretend the energy method provides a fully time-resolved fall trajectory or experimentally guaranteed maximum sag.
- If the existing numerical solver cannot represent the necessary states without an approximation, document its validity range and implement the best well-defined model supported by the current architecture instead of silently inserting arbitrary physical behavior.

## Test requirements

Add deterministic tests for at least:

- A leash attachment within a section **not** creating a structural split.
- An actual split connection retaining its behavior.
- Two otherwise identical cases with different standing COM height resulting in appropriately different gravitational-energy budgets.
- Different leash lengths affecting the final geometry/energy according to the documented leash convention.
- Backup sag contributing to the final COM height exactly once.
- Invalid geometry or nonconvergence producing an explicit error rather than a misleading successful result.
- Existing unaffected scenarios remaining consistent with reference tests/Python calculations where feasible.

Use meaningful physical invariants and numerical tolerances; do not hardcode an expected value solely to make a test pass. Avoid unrealistic parameter choices and assumptions of experimental validation.

## Scope and constraints

- Favor a targeted solver/model change over widespread UI refactoring.
- Preserve existing persistence/data formats or introduce a deliberate compatibility layer with tests if changing a model field is unavoidable.
- Preserve the original Python app as a reference; do not rewrite it unnecessarily.
- Do not implement a sliding leash, generic friction model, new material curves, the force–elongation curve explorer/overlay plotter, or other later roadmap features.
- If essential missing quantities make the model underdetermined, explicitly document the assumption and choose a minimal, safe default consistent with the existing app rather than inventing calibration data.
- Update the corresponding Phase 0/1 checkboxes in `ROADMAP.md` only for work actually completed.

## Final report

Return:
1. Root causes found and how the corrected geometry/energy equations work.
2. Changed files and any changed input semantics.
3. Tests run, including commands and results.
4. Known limitations, unresolved physics questions, and recommendations for Phase 2 (debug reports).
5. Anything you could not verify or complete.
