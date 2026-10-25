# Phase 1 follow-up — independent support, 2026-10-08

This supersedes the shared-ring conclusion in PHASE01_PHYSICS.md and the original
phase01 validation report. Those documents are retained as historical evidence.

## Reproduced causes

The old builder reused one Cartesian node for both cables at the walker. Keeping
that node out of `splits` did **not** prevent force transfer: incident main and backup
elements shared a degree of freedom. Earlier continuity/metadata tests missed this.
Two new pre-fix tests both failed: only genuine splits may share cable nodes, and
an isolated severed main must carry its own hanging weight rather than walker load.
Evidence: `validation/phase02/connectivity-before.{log,xml}`.

The old “Main peak” was the largest element tension **in the selected configuration**,
not a historical peak or necessarily the pre-failure value. Its loaded main segment
could support the walker through the erroneous shared attachment. The reported
6.9380/7.0089 kN screenshot has no complete input fixture, so those exact numbers
were not independently reproduced. No force is manually zeroed by the fix.

The drawing problem combined that real load-path defect with a leash drawn in the
same color as a tiny body at whole-span scale. Metadata alone could not fix it.

## Corrected model and states

`Network.buildNetwork` gives interior main and backup material labels distinct node
IDs. Each webbing remains continuous through its own numerical node. Real splits
(including a walker exactly at a split) still share degrees of freedom. Coincidence
in screen space alone is never used to decide mechanical connectivity.

`LeashAttachment` stores both nodes and a supporting cable. Standing applies the
walker point mass to main only. `breakMain` duplicates the cut endpoint, preserves
all material and mass, and transfers the walker support to the corresponding fixed
backup label. The former main attachment becomes an ordinary webbing node. Other
main sections can still carry load through genuine split connections.

| State | Support and interpretation |
|---|---|
| Standing | Intact main supports standing walker; backup sags independently between real connections. |
| Catch | Main cut; backup supports vertical leash. Fixed-height equilibrium plus energy root estimates the turning configuration. The prescribed coordinate reaction is not a free-force residual. |
| Rest | Same severed topology and backup support, fully relaxed static equilibrium; lower potential than Catch. |

The force laws, installation calibration, physical-point-first mesh, reference
lengths and masses, and numerical tolerances are unchanged. This intentionally
changes Android Simple's **topology** relative to Python's common LeashRing. Python
production remains untouched. Historical common-ring goldens still run through a
**test-only** topology adapter; new independent-support goldens use Python's existing
force/energy/equilibrium kernels with an explicit DOF-separation adapter in
`scripts/export_independent_reference.py`. Neither is experimental validation.

## Geometry and energy

Upward-positive y; body height H, physical ring-to-harness leash length L:

```
z0 = y_main,standing + 0.55 H
harness_final = y_backup,final - L
zf = harness_final + 0.05 H
ΔEg = mg (z0 - zf)
E = U_main + U_backup + V_webbing + mg z_COM
E_catch = E_standing
```

Final backup deflection is already in `y_backup,final`; no extra sag/drop term is
added. Cut tails retain elastic and gravitational energy. No arbitrary loss is
introduced at support transfer. Catch-to-rest potential decrease is separately
reported as ideal relaxation loss. The verified component ledger is reused.

This is **prescribed ideal capture at a fixed backup material label**, not a resolved
slack-leash trajectory, impact or demonstration that the initially slack leash
already spans the independently sagging backup. The fixed-attachment slack-fall
helper is a geometric diagnostic, not the actual two-webbing trajectory. Oblique
leash motion, impact losses, body rotation and sliding remain outside this model.
L < H/2 is rejected under the retained standing geometry. At L = H/2 the upright
head reaches the support height; this limiting geometry is not a clearance guarantee.

`ui/ResultGeometry.kt` provides physical attachment, harness, COM, feet, limbs and
leash coordinates shared by drawing and diagnostics. Phase 2 removes the magnified leash detail. The single full-span drawing uses
equal x/y metre scales and includes the whole body bounds. Solid portions of the
actual incident backup edges keep the webbing visible through the ring without
changing the independent-support mechanics. The leash has its
own dashed appearance/legend, a harness dot and COM cross. Forces are explicitly
labelled pre-/post-failure **spatial maxima**, not transient peaks.

## Verification

22 JVM tests pass: 3 separate-support, 11 attachment/energy, 8 reference methods.
Coverage includes 1/2/3 sections, walker movement, real-split coincidence, preserved
inventories and continuous webbing, correct support state, body/render coordinates,
energy closure, distinct Rest, invalid geometry and unchanged saved-input meanings.
The 8 reference methods include all 59 laws, meshes, 5 historical and 5 new Python
network cases at the original comparison tolerances. Build and lint pass.
Device verification and final totals are recorded in `validation/phase02/REPORT.md`.

No sliding, calibrated impacts, general spatial convergence or experimental safety
validation is established. A cut exactly at a walker/real split remains unsupported.

Phase 2 final drawing/diagnostic verification and environment-blocked device checks
are recorded in [the Phase 2 report](validation/phase02/REPORT.md). The 22-test count
above describes the pre-Phase-2 follow-up; the current active JVM suite has 31 tests.
