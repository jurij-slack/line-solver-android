# Phase 0/1 — attachment and standing-fall audit

2026-10-08. Scope: Android Base / Simple only. Python is unchanged; no sliding,
uniform generator, storage library, export workflow or later roadmap phase added.

## Findings before changes

The reported symptoms do **not** exactly describe the retained implementation:

1. `energy/Network.kt::buildNetwork` inserted a shared main/backup ring/load node
   inside a section, but **did not put it in `splits`**. Each cable had one incoming
   and one outgoing element there, with continuous material labels and unchanged
   reference inventory. No STOP/PASSABLE dispatch ran in this Simple solver. Python
   `network.build_network` does the same through `LeashRing`. The existing shared
   ring constrains the two webbing points to coincide; it is not a cut, section
   junction, or invented end stop. The pre-change continuity and real-split tests
   passed. We cannot truthfully claim to have reproduced a spurious physical split.
2. `SimpleSolver.solve` **already** formed the catch budget from standing energy plus
   `mg*0.55*bodyHeight`, while `breakMain` assigned the hanging offset
   `-leash + (0.55-0.5)*bodyHeight`. Thus standing-to-hanging drop and final sag were
   already included in the actual catch root. Adding another `mg*(h+L)` would be wrong.
3. A concrete inconsistency **was reproduced**: the returned `standing.value.total`
   omitted standing COM height although `initialEnergyJ` included it. In the retained
   35 m Parsec/MOTM / 80 kg / 1.8 m / 1.5 m leash case they were **−901.494044419 J**
   and **−124.542044419 J**, a **776.952 J** difference. This is a state/energy
   representation bug, not missing fall energy in the old root calculation.
4. `walker` actually named the ring/load node. Person offsets were duplicated between
   solver and drawing. The fixed-size body glyph and an approximate screen-coordinate
   leash endpoint could visually put the suspended walker above the attachment.
   These representations made the physical assumptions hard to inspect.

Pre-fix evidence: [three regressions](validation/phase01/pre-fix-regressions.xml),
[log](validation/phase01/pre-fix-regressions.log): **2 pass / 1 fail**. Existing
seven-test Python-reference baseline was green (Gradle reused up-to-date results).

## Architecture / audited source

All Android paths here are below `app/src/main/java/org/linesolver/base/`.

| Step | Implementation and reference |
|---|---|
| Form / storage | `ui/BaseScreen.kt` → shared `model/Draft` → `Draft.validate`; JSON in `SetupViewModel` / Android `SavedStateHandle`. Stored strings and field meanings are unchanged. |
| Setup | `LineSetup`, explicit `Section`, `Walker`, `Cut`; SI internally, pretension entry kN → N. Both main/backup are first-class. |
| Python generator audit | `src/line_solver/rig.py::prepared_section_lengths`, `generate_default_rig`; `rig_interactive.py::uniform_section_specs`, `rig_workflow.py` override. Prepared reference lengths are spacing plus final remainder (or explicit lengths), not fixed spatial split locations. Android generator remains Phase 5. |
| Installation | `SimpleSolver.calibrate`; Python `rig_solver.calibrate_rig`. Right weblock shortening, actual per-section materials, backup ratio scaled during installation then locked. |
| Topology | `energy/Network.kt::buildNetwork`; Python `network.build_network`. Real section connections share DOFs; internal connections free, anchors fixed. `contact-v1` physical points precede element allocation. |
| Person / attachment | New `model/WalkerGeometry.kt`, explicit `contact/LeashAttachment`, independent material labels. Python `highliner.Highliner`, `Leash`, `LeashRing`. |
| Cut / catch | `SimpleSolver`, `breakMain`, `hangingGuess`; Python `failure.break_mainline`, `hanging_tail_initial_guess`, `estimate_energy_catch` energy convention. Android uses fixed labels, not Python's sliding catch. |
| Numerical solve | `Equilibrium.solve`, same 2e-6 N force criterion, default 800 iterations, regularized Newton/Armijo. Optional smaller iteration budget supports a deterministic failure regression; defaults/tolerances unchanged. |
| Energy / results | `Network.evaluate`, `energy/FallEnergy.kt`, `StaticState.walkerElevations`; UI displays solver-owned state/ledger rather than deriving body geometry again. |

Materials retain tension-only PCHIP, consistent antiderivative and flagged bounded
MBS continuation. Optimizer trial continuation remains separate from strict accepted
material-domain checks. A Newton failure is a solver warning, not proof of physical
rupture. The Python files and curated material database retain their oracle hashes.

## Changes and exact conventions

`LeashAttachment` is explicitly separate from the list of structural connections.
It identifies the numerical ring node and both fixed material coordinates. It has no
connection STOP/PASSABLE property. A real split retains its node and constraints; a
prescribed main cut alone duplicates an endpoint. Attachment metadata, backup
continuity and each cable's reference length/mass survive that cut unchanged.

**Retained approximation:** the common ring encircles both webbings and collocates
their fixed material labels, even while the walker leash is slack. This was already
the Python/Android model. We did not silently replace it with independent lines or
a freely moving/sliding ring. A model in which the backup initially hangs outside
the ring would require an explicit different contact/engagement definition.

The input `walkerHeight` is still **body height H**, not a newly reinterpreted COM
height. The input `leash` remains physical ring-to-harness length **L**, not effective
ring-to-COM length. Python fractions are retained:

```
c = 0.55 H                  standing feet-to-COM height
h = 0.50 H                  feet-to-harness height
z0 = y_attachment,0 + c     initially standing on the mainline
zf = y_attachment,f - L + (c - h)   final leash-supported COM
```

`y` is positive upwards, and all elevations share the anchor datum. The reduced
model uses an upright walker and a vertical, massless, inextensible engaged leash;
it does not simulate oblique swing or a finite-stiffness leash. Walker/cut position
inputs are **main reference metres**, not horizontal x. The initial scenario is
standing, with slack `L-h` (or just taut upwards at `L=h`). `L<h` is impossible for
this geometry and is rejected. No initially-hanging option previously existed and
none is added in this phase. Equilibrium-only inputs lacking body height retain the
original point-load reference; no COM height is fabricated for them.

For a stationary attachment, downward body translation `d` gives:

```
z_COM(d) = y_attachment + c - d
z_harness(d) = y_attachment + h - d
slack(d) = L - abs(h - d),       0 <= d <= h + L
```

At `d=h+L` the slack becomes zero and COM equals the supported expression, with no
potential discontinuity. `duringSlackFall` is a **geometry/continuity diagnostic**,
not a simulated path or a claim that the ring stays stationary during the real fall.
The actual catch root uses the final solved ring/backup geometry.

## Energy balance

For main/backup elastic energy `U`, webbing gravity `Vw`, and walker mass `m`:

```
E0 = Umain,0 + Ubackup,0 + Vw,0 + mg z0
Ef = Umain,f + Ubackup,f + Vw,f + mg zf
Ef - E0 = 0                 ideal Simple turning configuration
ΔEg,walker = mg (z0 - zf)
```

The standing network now contains `mg*c` itself. The catch reads its total directly;
the former separate `mg*c` addition is removed. The resulting catch budget and
force/geometry predictions remain consistent with the original Python oracles.
Reference tests explicitly add the known constant to historical standing outputs,
without rewriting fixtures or weakening comparison tolerances.

For unchanged body geometry the same drop can be expanded as
`mg * [(y0-yf) + L + h]`. This is an **identity for auditing**, not an extra energy
term. Backup sag enters `yf` once. Both cable elastic energies and distributed
webbing gravitational energies remain in the balance, including released main tails.
No main material, mass, energy or backup force is silently discarded at the cut.

Changing body height changes initial COM by `0.55 ΔH`, but also changes the
harness-to-COM offset by `0.05 ΔH`; at fixed ring positions the net available drop
changes by `0.50 ΔH`. Tests verify this distinction rather than assuming body height
and COM height can be interchanged. Longer L changes both engagement geometry and
the final energy root. Translating the common height datum does not change the
released energy, strains or catch forces.

The turning model assumes kinetic energy is zero at the computed configuration.
There is **no arbitrary engagement dissipation**, sliding friction, or added leash
strain energy. Catch-to-rest potential decrease remains separately reported as the
existing ideal relaxation loss. Slack-leash impact dynamics, ring acceleration,
body rotation and redistribution of kinetic energy among webbing modes are not
resolved. A maximum-deflection estimate in this reduction is not a guaranteed
real-world bound or experimentally validated peak force.

## Regression scope / Phase 2 handoff

`AttachmentEnergyTest.kt` covers internal attachment continuity, real split behavior,
cut inventories/metadata, standing-state energy, height/leash sensitivity, slack
engagement and zero-drop limits, sag counted once, datum invariance, old JSON
semantics, invalid geometry and explicit nonconvergence. Existing material/mesh and
five full-network Python comparisons remain active. Retained validation is under
`validation/phase01/`; see its [report](validation/phase01/REPORT.md).

Phase 2 can serialize the already available attachment, posture, body/harness/COM
geometry and `FallEnergyLedger` alongside submitted inputs, material IDs, residuals
and errors. No exporter/report UI or new persistence system was implemented here.
Still unresolved: real slack-ring geometry if it does not collocate both lines,
oblique leash motion/impact losses, experimental validation and spatial resolution.
Those limitations cannot be fixed by adding another gravitational-energy correction.
