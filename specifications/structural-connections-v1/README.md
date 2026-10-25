# Android structural connections v1 / rig library v4

Portable JSON, UTF-8. No Python reader or hardware physics is implemented here.
Runtime SI units and all existing numerical models are unchanged. Library versions
1–3 remain readable and retain their exact original physical inputs. New connection
records require library version 4; `connections.schemaVersion` is 1. Unknown fields
in rig-library interchange and unknown schema/enum values fail strict decoding.
The working activity draft includes the same fields.

`modelConnections` is boolean, default false when absent. It belongs to the saved
rig as well as shared Solver Settings. Existing gravity/mesh/resistance and walker
settings keep their previous global ownership. Reset Rig retains solver settings
and replaces the physical rig. Restore Defaults disables the toggle. Activity
restart retains the working rig's explicit choice; a fresh draft can inherit a
previous explicit preference. Loading any legacy rig sets the toggle false.

Sections use their existing `id`. Generated Simple sections additionally retain
`uniform.instanceIds` in generation order. Legacy blank IDs are reconstructed with
UUID-v3 namespace strings `legacy-section:<zero-based-index>`; these are migration
identities within a rig, not labels or product IDs. New UI instances receive random
UUIDs. Identical Simple/Mixed conversion preserves instance identities.

A connection layout contains:

- `joints`: current physical incidence records, in traversal order.
- `detached`: removed/replaced annotated joints retained for explicit user review.

Each joint stores opaque `id`, `ends`, `kind`, `details`, `declaredCrossLine`.
An end is `{instanceId, cable: MAIN|BACKUP, port: start|end}` in installed direction.
An installed bungee's ports belong to the whole assembly, independent of rubber
layers, equal folded legs or computational subdivisions. Kinds are `serial`,
`branch`, `weblock_termination`, `anchor`, `explicit_cross_line`.

`details` contains `notes` and a list of independent `hardware` items. Each item
has opaque `id`, nullable `productId` and `revision` (both null or both present),
`configuration` text, and `attachmentPorts` (a unique subset of its joint's ends).
There are zero, one or several items; they have no mass, strength or force-sharing
calculation. Unpopulated slots are labeled pending Phase 6. Referenced products
cannot resolve in Phase 5 and fail with an actionable error, even when hidden.
No nominal strengths are summed. Custom material snapshots remain separate.

## Physical graph and numbering

The canonical incidence builder mirrors Phase 4's endpoint operations: shared
existing section splits/anchors, cable-specific serial insertions, series-right
webbing stop, and section-parallel assemblies at the current webbing endpoints.
It groups all incidence at one vertex, not one joint per cable pair. Physical ports
map to the actual solver-owned first/last edge endpoints. Runtime mapping rejects
any declared incidence whose endpoints are not one node. No coordinates, meshing,
walker contact or drawn intersections determine connectivity.

Traverse MAIN in section order, before-section banks in saved bank order, each
bank start then end, section start then end, then right-terminal banks. Repeat for
BACKUP. Emit each shared vertex once; append any remaining vertex in construction
order. Parallel branches have one junction at each end regardless of multiplicity
or folds. This ordering is based on installed IDs/order and typed ports, never
component names or screen coordinates. Anchor vertices are labeled Left/Right
attachment and consume no C index. All other vertices receive derived C1, C2, ... .
Existing shared main/backup splits may be branch joints; labeling does not add a
splice or change their original mechanics.

Thus a MAIN left bank followed by two sections has Bungee 1 — C1 — Section 1 — C2
— Section 2. Without that bank, Section 1 — C1 — Section 2. Parallel membership
changes do not create duplicated junctions. Solved drawings map the same IDs to
solver nodes; dense text labels are culled while the searchable overview retains
all joints. Unsolved bungee drawings provide an incidence schematic strip because
an equilibrium position is not yet available.

## Identity and edit policy

Match exact unordered incident port sets first. A unique subset/superset match
also preserves a joint when parallel membership changes, provided assigned ports
still exist and an explicit cross-line declaration remains valid. Unchanged
interfaces preserve IDs, independent item IDs, notes and declarations as numbers
shift. Legacy inference uses deterministic UUID-v3 of sorted incident-port records;
new/replaced interfaces on an already identified rig receive random UUIDs.

Never choose between multiple matching predecessors. Never merge annotations.
Removed/replaced annotated or declared joints go to `detached`, including hardware
with attachment ports that were removed. They are excluded from mechanics and
current numbering. The UI surfaces retained records even with the toggle off;
users review and explicitly discard them. Unannotated replaced interfaces are
removed. Recreating an interface after an edit assigns a fresh ID and does not
resurrect detached hardware. Removing a section targeted by a bungee is rejected
with an edit error; move/remove that bank first.

## Supported cross-line declaration and webblocks

Only an existing shared internal main/backup structural vertex may be explicitly
declared as a transverse tie. Both webbings retain their own edges, laws, masses
and material inventory. The existing shared node is the entire static constraint:
no additional spring, force, energy, mass or redundant weld is introduced. The
record persists when the toggle is off. This is an explicit declaration of an
already represented physical split, not support for sewing arbitrary interior
locations. Arbitrary internal seams, independently located end ports, anchor seam
creation and missing endpoints are rejected. No leash ring or apparent crossing
creates such a declaration. TypeX product/stitch data and seam failure are deferred.

A webbing/bungee series endpoint is an explicit structural termination. In
series-right order it is webbing → weblock stop → bank(s) → anchor. Independent
parallel assemblies share that stop. Optional slots cannot remove it or transfer
material through it. Inventory, ideal limiter constraints, terminal take-up and
all existing static calculations are untouched by the checkbox.

## Interchange and Phase 6/7 handoff

Saved Rigs offers JSON export/import. Import is additive and atomic: identical
entries skip, conflicting IDs/revisions or names reject the entire import; existing
rigs and the working draft are never replaced. Missing custom laws must be restored
from the separate My Materials package with their immutable revision. There is no
fallback model or product selection. Phase 6 can bind item IDs/revisions and typed
ports to a sourced hardware catalogue without another joint or multiplicity model.
Phase 7 must port this schema and supported incidence policy independently; no
new-feature Kotlin/Python parity is claimed.

### Phase 6 UI refinement: boundary TypeX annotation (rig schema 6)

`JointDetails.typeX` is nullable and absent in schemas 1–5. Absence means no
TypeX annotation; an old `declaredCrossLine` remains its original structural
declaration and is never silently converted to a TypeX product or seam rating.
The connection-layout version remains 1. A non-null seam requires rig-library
schema 6. JSON fields: `state` (`intact` or `broken`), `assembly` and `source`
(strings), `strengthN` (null or finite positive SI force), `lowerBound` (boolean).
A known strength requires both assembly and source. Unknown strength remains null.

Placement requires an existing common internal section boundary with both adjacent
webbing ends present on MAIN and BACKUP. A modeling split may describe continuous
webbing: it does not create a physical splice or hardware item. Dedicated main
and backup longitudinal items use the existing roles and immutable specification
references. Shared/unassigned legacy items retain their ports, roles, IDs, state
assumptions, notes, revisions and mass accounting, and remain available in Advanced
details. Choosing Continuous removes only that line's dedicated items in the
uncommitted editor when there is no shared/unassigned legacy hardware; Cancel
preserves the stored record. With legacy hardware it opens Advanced for explicit
review instead. Shared items are never silently deleted or reassigned.

TypeX is transverse and independent of those longitudinal items. Additional seam
mass is neglected in this simplified model. Seam state/strength is an annotation,
not a longitudinal rupture criterion: it never changes webbing MBS, removes edges,
adds force or energy, or physically releases the shared main/backup node. Both
intact and broken states retain the existing common-node limitation, including
when Model connections is off. There is no calculated transverse seam load.
The optional documented Slacktivity assembly selection records 12000 N as a lower
bound with the exact assembly/source, never as a universal exact failure threshold.
Removing/replacing a boundary retains annotated records in the existing detached
list. Labels remain derived; all graph links resolve opaque stable IDs.

### Current Android UI policy (2026-10-18)

The TypeX annotation above is retained for lossless compatibility and deferred
Dynamics development; it is not an active Android feature. No seam controls or
transverse custom connector selections are exposed in either setup workflow or
Dynamics. Unsupported records receive a generic compatibility warning. Existing
schema-6 fields, generic declarations, immutable hardware references and detached
records round-trip unchanged. Ordinary connection edits preserve them by copying
the existing details record. No replacement regular type is written over a seam.

Transverse hardware contributes no active concentrated mass, required-mass error,
strength diagnostic or mass-cache signature. Ordinary structural nodes remain
unchanged. Regular groups require independently specified Main/Backup connectors;
missing records stay unresolved instead of being described as continuous webbing.
Anchor attachments remain ideal, non-failing and non-editable. Archived source and
necessary future Dynamics work: `deferred/typex/README.md`.
