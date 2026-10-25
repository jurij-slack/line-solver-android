# Android static bungee exchange v1

Phase 4 extends the existing component catalogue and local material package;
there is no independent curve database. Python integration is Phase 7.

## Model and measurement identity

Products use existing category `bungee`. Loading observations remain immutable
`MeasurementDataset` snapshots; PCHIP/provisional models use the same points,
domain checks, exact `ModelReference` and content seal as webbing models.
`MaterialModel.specifications.information.bungee_law` carries a versioned payload:

```json
{
  "schema_version": 1,
  "basis": "physical_cord",
  "tested_physical_length_m": 2,
  "tested_legs": 1,
  "scalable_per_length": false,
  "scaling_evidence": "",
  "rubber_layers": null,
  "total_mass_kg": null,
  "source": "Source citation for measurement basis/configuration",
  "ideal_equal_legs": false
}
```

The numbers above illustrate the wire format, not a product or a default. Users
must supply a measured physical reference length, source and loading observations.
Protocol/preconditioning/conditions remain in the cited dataset and raw editor
snapshot. Absolute extension input requires a gauge equal to tested physical
length divided by tested legs. Runtime strain is engineering strain, force N,
mass kg, reference linear mass kg/m, length m and energy J.

`physical_cord` means one unfolded physical cord. `folded_assembly` means the
measured total assembly force at its tested leg count. `strand_or_layer` retains
that evidence but is rejected for installed mechanics without a verified assembly
conversion. Whole-cord/folded observations stay restricted to tested physical
length. Only a physical-cord law with explicit per-length scaling evidence can
use other physical lengths; it also needs reference linear mass. Layer count is
metadata and never scales force. Unknown strength remains unknown.

For a physical-cord law, n ideal equal legs have effective rest L0/n, force n*T,
energy L0*integral(T dε), limiter engagement separation Llim/n and one cord mass.
For a measured folded-assembly law, force is used once and energy uses tested
installed separation L0/n; converting its leg count is rejected. Folding requires
an explicit ideal-equal-leg assumption, negligible bend/termination length and
no redirects, friction or unequal legs.

## Installed rigs

Old rig libraries retain schema 1/2 adapters and original JSON defaults. New rig
libraries containing a `configuration.bungees` extension encode schema 3; old
apps reject schema 3 instead of losing its mechanics. The bungee layout itself
uses `schemaVersion: 1`, in the existing rig codec's camelCase convention.

A layout contains ordered `banks`. A bank has a unique stable `id`, `placement`
(`series_before`, `series_right`, `parallel_section`), `sectionId` when relevant,
`cable` (`MAIN`/`BACKUP`) and independent `assemblies`. Referenced section drafts
have stable IDs assigned on first attachment editing. Removing a referenced
section produces a validation error; it never silently reattaches a bungee.

An assembly stores its stable ID, exact model reference (ID/revision/fingerprint,
version/batch/datasets), physical unfolded `physicalRestM`, `equalLegs`,
`physicalCordCount` (currently exactly one), optional total physical
`limiterPhysicalLengthM`, enable flag, installed-length source and uncertainty.
Separate physical bungees are separate assembly records, even if identical.
The bank and referenced section/anchor identify physical endpoints; numerical
mesh IDs are not serialized physical joint/hardware identities.

Banks at one series location retain list order. Their parallel assemblies share
terminal nodes, but retain separate mesh paths, masses, energies and limiters.
A section-parallel bank connects that cable's current physical section endpoints.
Right-side banks follow webbing → weblock termination → bungee → anchor. All
insertions are cable-specific; the other cable's elements/contact ownership are
preserved. The existing shared section joints remain physical joints. A walker
contact is not a new joint. Calibration takes up terminal webbing only, never
bungee inventory, and retains existing backup extra-length semantics.

## Static numerical treatment

Rubber paths use existing native material energy/gradient/Hessian evaluation.
Equal-leg paths are meshed with the selected 2–32 segments and one physical mass
ledger. Gravity uses half of each segment's actual mass at either node, matching
the existing webbing discretization. The whole folded assembly's centerline path
length is the sum of segment lengths; its ideal constraint is
`c = Lpath - LlimPhysical/n <= 0`. There is one constraint per assembly, never one
full-length limiter per mesh segment.

Active-set SQP solves energy stationarity plus active path constraints using the
analytic length Jacobian/Hessian and KKT multipliers in N. Positive multipliers
are tensile limiter reactions. Merit globalization restores infeasible initial
guesses; a near-feasible Newton check uses KKT residual decrease when energy
subtraction loses precision. Negative multipliers release an active constraint.
Final acceptance requires stationarity <= 2e-6 N (or the supplied tolerance),
maximum penetration <= 1e-9 m, nonnegative multipliers within force tolerance and
complementarity <= max(1e-7 J, forceTolerance*1e-3 m). Strict material-domain checks
run before returning a result. There is no force/length clipping or penalty spring.

Dependent active constraints retain every primal check. Individual reaction shares
are indeterminate and shown as such; internal representative multipliers are used
only to compute aggregate stationarity/anchor reactions. Prescribed infeasible
geometry, collapsed segments, unresolved sets and resource excess fail explicitly.
Rubber elastic energy excludes work stored by an ideal inextensible constraint;
its derivative is rubber force, not total tension when the limiter is taut.

Resource bounds: 32 banks, 64 independent assemblies, 1–16 equal legs; ideal
limiter solves additionally require <=512 coordinates. Unconstrained paths retain
the existing network bound. Large all-webbing workflows retain their old path.

## Unsupported paths

Finite-stiffness limiters have no shipped sourced law and are unavailable. No
mass/strength override without evidence, source-basis conversion, unequal folding,
redirect friction, main/backup splice, hardware catalogue, connection toggle or
numbering is introduced. Bungee rigs disable catch/failure; no transient impacts,
hysteresis, unloading/rate dependence, rupture cascade or advanced dynamics are
claimed. Static results are numerical research results, not certification or
experimental safety validation.
